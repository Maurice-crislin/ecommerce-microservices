INSERT INTO products (product_code, product_name, price, status) VALUES
                                                                     (10010001, 'Mechanical Keyboard', 199.99, 'ACTIVE'),
                                                                     (10010002, 'Wireless Mouse', 99.99, 'ACTIVE'),
                                                                     (10010003, 'Gaming Headset', 149.99, 'ACTIVE'),
                                                                     (10010004, 'USB-C Hub', 49.99, 'ACTIVE'),
                                                                     (10010005, '27-inch Monitor', 299.99, 'INACTIVE'),
                                                                     (10010006, 'External SSD 1TB', 129.99, 'ACTIVE'),
                                                                     (10010007, 'Webcam HD', 79.99, 'ACTIVE'),
                                                                     (10010008, 'Laptop Stand', 39.99, 'ACTIVE'),
                                                                     (10010009, 'Mechanical Keycap Set', 59.99, 'INACTIVE'),
                                                                     (10010010, 'Wireless Charging Pad', 29.99, 'ACTIVE')
    ON DUPLICATE KEY UPDATE product_name=VALUES(product_name), price=VALUES(price), status=VALUES(status);

INSERT INTO product_detail (product_id, brand, category_code, description) VALUES
    ((SELECT id FROM products WHERE product_code = 10010001), 'BrandA', 'ELECTRONICS', 'Mechanical Keyboard with RGB lighting'),
    ((SELECT id FROM products WHERE product_code = 10010002), 'BrandA', 'ELECTRONICS', 'Wireless Mouse with ergonomic design'),
    ((SELECT id FROM products WHERE product_code = 10010003), 'BrandB', 'ELECTRONICS', 'Gaming Headset with surround sound'),
    ((SELECT id FROM products WHERE product_code = 10010004), 'BrandC', 'ELECTRONICS', 'USB-C Hub with 7 ports'),
    ((SELECT id FROM products WHERE product_code = 10010005), 'BrandB', 'ELECTRONICS', '27-inch Monitor 4K resolution'),
    ((SELECT id FROM products WHERE product_code = 10010006), 'BrandC', 'ELECTRONICS', 'External SSD 1TB high speed'),
    ((SELECT id FROM products WHERE product_code = 10010007), 'BrandA', 'ELECTRONICS', 'Webcam HD 1080p'),
    ((SELECT id FROM products WHERE product_code = 10010008), 'BrandD', 'ELECTRONICS', 'Laptop Stand adjustable height'),
    ((SELECT id FROM products WHERE product_code = 10010009), 'BrandD', 'ELECTRONICS', 'Mechanical Keycap Set PBT material'),
    ((SELECT id FROM products WHERE product_code = 10010010), 'BrandA', 'ELECTRONICS', 'Wireless Charging Pad fast charge')
    ON DUPLICATE KEY UPDATE brand=VALUES(brand), category_code=VALUES(category_code), description=VALUES(description);