INSERT INTO inventory (product_code, available_stock, version) VALUES
                                                                   (1001, 50, 0),
                                                                   (1002, 100, 0),
                                                                   (1003, 200, 0)
    ON DUPLICATE KEY UPDATE available_stock = VALUES(available_stock);
