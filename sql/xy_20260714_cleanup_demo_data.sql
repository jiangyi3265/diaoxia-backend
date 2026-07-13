-- 清理自动化验收残留的乱码 E2E 门店，并把首个演示门店改成可读名称。
-- 仅下架 E2E 数据，保留其关联记录，避免破坏历史外键关系。

UPDATE xy_store
SET status = '1'
WHERE store_name LIKE 'E2E%';

UPDATE xy_store
SET store_name = '钓虾生活馆体验店',
    address = '大连市沙河口区（演示地址）',
    business_hours = '10:00-22:00'
WHERE store_name = '钓虾测试门店';

-- 原商品图实际为海鲜意面，修正名称和说明，避免图文不一致。
UPDATE xy_product
SET product_name = '鲜虾海鲜意面',
    category_name = '到店美食',
    detail_text = '鲜虾搭配海鲜与意面，适合钓虾间隙到店享用。'
WHERE product_name = '鲜香虾丸饵料';
