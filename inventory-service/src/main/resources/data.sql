INSERT INTO inventory (product_code, available_stock, locked_stock, sold_stock, version) VALUES
                                                                                             (1001, 50, 0, 0, 0),
                                                                                             (1002, 100, 0, 0, 0),
                                                                                             (1003, 200, 0, 0, 0),
                                                                                             (1004, 0, 0, 0, 0),
                                                                                             (1005, 5, 0, 0, 0),
                                                                                             (1006, 10, 0, 0, 0),
                                                                                             (1007, 500, 0, 0, 0),
                                                                                             (1008, 250, 0, 0, 0),
                                                                                             (1009, 1, 0, 0, 0),
                                                                                             (1010, 0, 0, 0, 0),
                                                                                             (1011, 1000, 0, 0, 0),
                                                                                             (1012, 30, 0, 0, 0)
    ON DUPLICATE KEY UPDATE
                         available_stock = VALUES(available_stock),
                         locked_stock = VALUES(locked_stock),
                         sold_stock = VALUES(sold_stock);
