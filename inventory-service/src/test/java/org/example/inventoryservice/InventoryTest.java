package org.example.inventoryservice;

import org.example.inventoryservice.domain.Inventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class InventoryTest {

    @Test
    void deductStock_success() {
        Inventory inventory = new Inventory(1001L, 10);

        inventory.deductStock(3);

        assertEquals(7, inventory.getAvailableStock());
    }

    @Test
    void deductStock_quantityInvalid() {
        Inventory inventory = new Inventory(1001L, 10);

        assertThrows(IllegalArgumentException.class,
                () -> inventory.deductStock(0));
    }

    @Test
    void deductStock_outOfStock() {
        Inventory inventory = new Inventory(1001L, 5);

        assertThrows(IllegalArgumentException.class,
                () -> inventory.deductStock(6));
    }
}

