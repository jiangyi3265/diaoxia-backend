-- 钓虾生活馆首批正式会员方案与商城商品。
-- 脚本可重复执行，按名称判断，不覆盖后台已调整的数据。

INSERT INTO xy_membership_plan(plan_name, amount, duration_days, daily_reservation_limit, status, sort_order)
SELECT '月享会员', 88.00, 30, 1, '0', 10
WHERE NOT EXISTS (SELECT 1 FROM xy_membership_plan WHERE plan_name = '月享会员');

INSERT INTO xy_membership_plan(plan_name, amount, duration_days, daily_reservation_limit, status, sort_order)
SELECT '季度畅钓会员', 258.00, 90, 1, '0', 20
WHERE NOT EXISTS (SELECT 1 FROM xy_membership_plan WHERE plan_name = '季度畅钓会员');

INSERT INTO xy_membership_plan(plan_name, amount, duration_days, daily_reservation_limit, status, sort_order)
SELECT '年度尊享会员', 888.00, 365, 2, '0', 30
WHERE NOT EXISTS (SELECT 1 FROM xy_membership_plan WHERE plan_name = '年度尊享会员');

INSERT INTO xy_product(product_name, category_name, cover_url, detail_text, sale_price, member_discount_enabled, stock, status, sort_order)
SELECT '轻量钓虾竿', '钓虾装备', '/media/rod.jpg', '轻巧顺手的入门钓虾竿，到店娱乐和日常练习都适合。', 39.90, 1, 50, '0', 10
WHERE NOT EXISTS (SELECT 1 FROM xy_product WHERE product_name = '轻量钓虾竿');

INSERT INTO xy_product(product_name, category_name, cover_url, detail_text, sale_price, member_discount_enabled, stock, status, sort_order)
SELECT '鲜香虾丸饵料', '饵料', '/media/shrimpball.jpg', '适合门店钓虾场景的鲜香虾丸饵料，开袋即可使用。', 18.80, 1, 80, '0', 20
WHERE NOT EXISTS (SELECT 1 FROM xy_product WHERE product_name = '鲜香虾丸饵料');

INSERT INTO xy_product(product_name, category_name, cover_url, detail_text, sale_price, member_discount_enabled, stock, status, sort_order)
SELECT '招牌秘制蘸料', '到店美食', '/media/sauce.jpg', '门店招牌风味蘸料，搭配现钓鲜虾更香。', 12.80, 1, 100, '0', 30
WHERE NOT EXISTS (SELECT 1 FROM xy_product WHERE product_name = '招牌秘制蘸料');

INSERT INTO xy_product(product_name, category_name, cover_url, detail_text, sale_price, member_discount_enabled, stock, status, sort_order)
SELECT '香酥薯条', '到店美食', '/media/fries.jpg', '现点现炸，适合钓虾间隙与好友一起分享。', 16.80, 1, 60, '0', 40
WHERE NOT EXISTS (SELECT 1 FROM xy_product WHERE product_name = '香酥薯条');

INSERT INTO xy_product(product_name, category_name, cover_url, detail_text, sale_price, member_discount_enabled, stock, status, sort_order)
SELECT '冰爽啤酒', '酒水饮料', '/media/beer.jpg', '冰镇啤酒，到店自提。本商品暂不参与会员折扣。', 12.00, 0, 72, '0', 50
WHERE NOT EXISTS (SELECT 1 FROM xy_product WHERE product_name = '冰爽啤酒');

INSERT INTO xy_product(product_name, category_name, cover_url, detail_text, sale_price, member_discount_enabled, stock, status, sort_order)
SELECT '海鲜分享拼盘', '到店美食', '/media/seafood.jpg', '适合多人分享的海鲜拼盘，建议提前下单，到店享用。', 68.00, 1, 30, '0', 60
WHERE NOT EXISTS (SELECT 1 FROM xy_product WHERE product_name = '海鲜分享拼盘');

INSERT INTO xy_product(product_name, category_name, cover_url, detail_text, sale_price, member_discount_enabled, stock, status, sort_order)
SELECT '鲜活大虾加购份', '到店加购', '/media/prawn.jpg', '到店钓虾加购份，按门店当日供应情况提供。本商品不参与会员折扣。', 38.00, 0, 40, '0', 70
WHERE NOT EXISTS (SELECT 1 FROM xy_product WHERE product_name = '鲜活大虾加购份');
