下面给出一个**完整的技术方案**，满足“Redis 缓存预扣库存 + DB 权威数据源 + 全流程幂等控制”的目标。方案会同时保留你原有的 DB 乐观锁机制，并与 Redis 结合。

---

## 一、整体架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                        客户端请求                            │
│    (下单 lock / 支付 confirm / 超时 unlock)                  │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                      库存服务（应用层）                       │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  1. 幂等检查（先 Redis SET NX，双写到 DB 幂等表）     │   │
│  │  2. 执行 Redis Lua 脚本（原子操作 available/locked）  │   │
│  │  3. 若为支付成功，发送异步消息 / 同步写 DB（乐观锁）   │   │
│  │  4. 定时对账 + 缓存重建任务                           │   │
│  └──────────────────────────────────────────────────────┘   │
└───────────────┬─────────────────────┬───────────────────────┘
                │                     │
                ▼                     ▼
        ┌──────────────┐      ┌──────────────┐
        │ Redis 缓存层  │      │   MySQL DB   │
        │ - available  │      │  inventory   │
        │ - locked     │      │  - available │
        │ - 幂等key     │      │  - locked    │
        └──────────────┘      │  - sold      │
                              │  - version   │
                              │  idempotency │
                              │    (幂等表)   │
                              └──────────────┘
```

---

## 二、数据存储设计

### 2.1 数据库表（保持不变）

```sql
CREATE TABLE inventory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_code BIGINT NOT NULL UNIQUE,
    available_stock INT NOT NULL,   -- 真正可售库存（扣除锁定和已售）
    locked_stock INT NOT NULL,      -- 已锁定未支付库存
    sold_stock INT NOT NULL,        -- 已支付完成库存
    version BIGINT NOT NULL         -- 乐观锁
);

-- 幂等表（记录已处理的操作）
CREATE TABLE idempotency_record (
    idempotent_key VARCHAR(255) PRIMARY KEY,  -- 如 "lock:order123"
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_at (created_at)
);
```

### 2.2 Redis 数据结构

**库存 Hash**（Key: `inventory:{productCode}`）：
- `available`: 剩余可锁定量（对应 DB 的 `available_stock`）
- `locked`: 当前已锁定未支付量（对应 DB 的 `locked_stock`）

**幂等 Key**（String，TTL=5分钟）：
- `idem:{action}:{orderId}`，例如 `idem:lock:order123`
- 值随意（如 "1"），利用 `SET NX EX` 实现原子性。

---

## 三、核心操作流程（含幂等控制）

### 3.1 下单（lock）—— 只操作 Redis，不写 DB

| 步骤 | 说明 |
|------|------|
| 1 | 生成幂等key = `"idem:lock:" + orderId` |
| 2 | 尝试 Redis `SET idem:lock:order123 1 NX EX 300`<br>• 失败 → 返回“重复下单” |
| 3 | 执行 Lua 脚本（原子检查 + 扣减 available + 增加 locked） |
| 4 | Lua 返回结果：<br>• 1 → 成功，记录订单状态“待支付”<br>• -1 → 库存不足<br>• -2 → key 不存在，需从 DB 加载（见 3.4） |
| 5 | **异步**将幂等key写入 DB 幂等表（写失败不影响主流程，通过定时任务补录） |

**Lua 脚本（lock）**：
```lua
-- KEYS[1] = inventory key, KEYS[2] = idempotent key (optional, 但已在外部检查)
-- ARGV[1] = quantity
local available = redis.call('HGET', KEYS[1], 'available')
if not available then return -2 end   -- cache miss
available = tonumber(available)
if available >= tonumber(ARGV[1]) then
    redis.call('HINCRBY', KEYS[1], 'available', -ARGV[1])
    redis.call('HINCRBY', KEYS[1], 'locked', ARGV[1])
    return 1
end
return -1
```

### 3.2 支付成功（confirm）—— 操作 Redis + 写 DB

| 步骤 | 说明 |
|------|------|
| 1 | 幂等key = `"idem:confirm:" + orderId` |
| 2 | Redis `SET NX EX` 检查（防重复支付回调） |
| 3 | 执行 Lua 脚本（检查并扣减 Redis 中的 `locked`） |
| 4 | Lua 成功（返回1）后：<br>• **同步**调用 DB 的 `confirmSale` 方法（使用乐观锁）<br>• 若 DB 更新成功，更新订单状态为“已完成”<br>• 若 DB 更新失败（乐观锁冲突），重试 3 次，仍失败则发送告警并人工介入 |
| 5 | DB 更新成功后，可同时将幂等key写入 DB 幂等表（可选） |

**Lua 脚本（confirm）**：
```lua
-- KEYS[1] = inventory key, ARGV[1] = quantity
local locked = redis.call('HGET', KEYS[1], 'locked')
if not locked then return -2 end
locked = tonumber(locked)
if locked >= tonumber(ARGV[1]) then
    redis.call('HINCRBY', KEYS[1], 'locked', -ARGV[1])
    -- 注意：Redis 中不维护 sold，因此无需增加 sold
    return 1
end
return -1
```

**DB 的 confirmSale 实现**（利用乐观锁）：
```java
@Transactional
public void confirmSale(Long productCode, Integer quantity, String orderId) {
    Inventory inv = inventoryRepository.findByProductCodeForUpdate(productCode);
    // 前置检查：availableStock >= quantity? no, 实际上 confirm 只需检查 lockedStock
    if (inv.getLockedStock() < quantity) {
        throw new IllegalStateException("locked stock insufficient");
    }
    inv.setLockedStock(inv.getLockedStock() - quantity);
    inv.setSoldStock(inv.getSoldStock() + quantity);
    // availableStock 不变（因为锁定时已经扣减过 available）
    inventoryRepository.save(inv);  // version 自动更新
}
```
> **注意**：`confirmSale` 与之前 `lock` 的 DB 逻辑一致，但这里 Redis 的 lock 操作已经扣减了 available 并增加了 locked，所以 DB 中 `availableStock` 应该已经包含了锁定量吗？  
> **修正**：由于我们采用“Redis 缓存预扣，支付成功才写 DB”的方案，DB 中 `availableStock` 从未因 lock 而改变。因此 confirm 时，DB 的 `availableStock` 需要 **减去 quantity**，同时 `lockedStock` 也要减。更合理的方式是：
> - DB 中的 `availableStock` 代表真实可售卖库存（不包含锁定量）。
> - `lockedStock` 始终为 0（因为锁定量只存在 Redis 中）。
> - `soldStock` 累加。

为了避免困惑，建议简化 DB 模型：**只保留 `availableStock` 和 `soldStock`，去掉 `lockedStock`**。因为 Redis 负责所有锁定状态。这样支付成功时 DB 只需要：
```java
inv.setAvailableStock(inv.getAvailableStock() - quantity);
inv.setSoldStock(inv.getSoldStock() + quantity);
```
后面会基于此简化说明。

### 3.3 超时未支付（unlock）—— 只操作 Redis

| 步骤 | 说明 |
|------|------|
| 1 | 幂等key = `"idem:unlock:" + orderId` |
| 2 | Redis `SET NX EX` 检查 |
| 3 | 执行 Lua 脚本（恢复 Redis 中的 available，扣减 locked） |
| 4 | 更新订单状态为“已取消”，无需操作 DB 库存 |

**Lua 脚本（unlock）**：
```lua
local locked = redis.call('HGET', KEYS[1], 'locked')
if not locked then return -2 end
locked = tonumber(locked)
if locked >= tonumber(ARGV[1]) then
    redis.call('HINCRBY', KEYS[1], 'available', ARGV[1])
    redis.call('HINCRBY', KEYS[1], 'locked', -ARGV[1])
    return 1
end
return -1
```

---

## 四、幂等控制的双重保障

### 4.1 Redis 幂等（第一道防线）
- 每个操作之前执行 `SET key NX EX 300`
- 优点：性能高，防重复请求
- 缺点：Redis 宕机会丢失记录

### 4.2 DB 幂等表（第二道防线）
- 异步或同步将 `idem:lock:order123` 等 key 写入 DB 表
- 在 **支付成功** 写 DB 时，可以在同一个数据库事务中插入幂等记录（确保幂等与库存更新原子性）
- 对于 lock/unlock（不写 DB），可通过定时任务检查：扫描订单表，如果发现某个订单的 lock 幂等记录在 DB 中缺失，则补录，以应对 Redis 丢失后的重复请求。

### 4.3 降级策略
如果 Redis 连接失败（如网络故障），服务应降级为 **纯 DB 模式**：
- 直接调用原有的 Inventory 实体方法（使用乐观锁）
- 同时将幂等key 写入 DB 幂等表（不依赖 Redis）

---

## 五、缓存一致性维护

### 5.1 首次加载 / 缓存重建
- 当 Lua 脚本返回 -2（key 不存在）时，从 DB 加载当前库存：
  ```java
  Inventory inv = inventoryRepository.findByProductCode(productCode);
  redisTemplate.opsForHash().putAll("inventory:"+productCode,
      Map.of("available", inv.getAvailableStock().toString(),
             "locked", "0"));   // DB 中的 lockedStock 视为 0（因为 Redis 负责锁定）
  ```
- **注意**：加载时 `locked` 设置 0，意味着任何未支付的订单在 Redis 中丢失后需要重新 lock，可能导致库存占用超限，但这是可接受的（最终支付时 DB 会兜底检查）。

### 5.2 对账任务（每 5 分钟执行）
1. 从 DB 读取所有 `inventory` 记录（或分页）。
2. 对比 DB 的 `availableStock` 与 Redis 中的 `available` + `locked` 之和。
3. 若发现差异（例如 DB 剩余 100，Redis 中 available+locked=80），则以 DB 为准 **全量覆盖** Redis。
4. 同时检查订单表：对超过 30 分钟未支付的订单，强制执行 unlock（防止 Redis 中 locked 残留）。

---

## 六、异常场景处理

| 场景 | 处理方式 |
|------|----------|
| Redis 执行 lock 成功，但应用宕机未记录订单 | 订单表无记录，Redis 中的 locked 会一直存在 → 对账任务会将该 locked 视作“孤儿”，通过扫描订单表（不存在对应待支付订单）将其解锁。 |
| 支付成功时 DB 更新失败（乐观锁冲突） | 重试 3 次，仍失败则记录异常并告警，同时暂不修改订单状态（保持待支付）。人工介入或自动补偿：重新从 DB 加载库存，再次尝试 confirm。 |
| Redis 宕机恢复后，部分 unlocked 丢失 | 对账任务会发现 DB 中的 availableStock 与 Redis 中不一致，全量覆盖 Redis。丢失的 lock 记录会导致该订单在支付时失败（DB 库存不足），需退款重试。 |
| 幂等 key 在 Redis 中已过期，但第二次请求到达 | 如果 DB 幂等表中有记录，则拒绝；否则允许。这可能导致少量重复，但影响可控。 |

---

## 七、优点与面试话术

**优点总结**：
- 高并发下单（Redis 单机几万 QPS）
- DB 只处理支付成功写操作，压力极小
- 全流程幂等控制（Redis + DB 表）
- 最终一致性 + 对账兜底，保证数据不丢失
- 保留原有乐观锁作为 DB 最后的并发控制

**面试时你可以说**：
> “我设计了一个基于 Redis 预扣库存、DB 作为权威数据源的库存系统。所有下单操作通过 Redis Lua 脚本原子修改 available 和 locked 字段，并利用 Redis SET NX 实现轻量级幂等。支付成功时，先更新 Redis 释放 locked，再同步更新 DB（使用乐观锁防止冲突），最终通过定时对账任务修复缓存与数据库的不一致。同时准备了降级方案：Redis 故障时直接切换到数据库乐观锁模式。这个方案让我深入理解了缓存一致性、幂等设计和高并发系统的权衡。”

---

## 八、可选的进一步优化

- **消息队列异步写 DB**：支付成功后发消息，批量更新 DB，进一步提升吞吐量。
- **Redisson 分布式锁**：用于防止同一商品并发加载缓存导致的“缓存击穿”。
- **Lua 脚本结合 `redis.replicate_commands()`**：确保脚本在集群模式下正确复制。

希望这个完整的方案能帮助你在面试中清晰地展示你的设计能力！