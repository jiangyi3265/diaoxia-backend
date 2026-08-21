-- 2026-08-21：修复商品主图无法显示。
--
-- 历史种子数据把主图写成 `/static/img/xxx.jpg`。该路径是小程序包内路径，
-- 客户端会把相对地址拼到接口域名上，请求 https://<api>/static/img/xxx.jpg，
-- 而后端并不提供该路径（被 Spring Security 拦截返回 401 JSON），因此图片全部空白。
-- 后端现在通过 `/media/**` 公开随包发布的内置图片，这里把历史数据改到该地址。
--
-- 本脚本可重复执行，且只改动仍指向包内路径的记录，不会覆盖后台上传的 `/profile/upload/...` 主图。

UPDATE xy_product
SET cover_url = REPLACE(cover_url, '/static/img/', '/media/')
WHERE cover_url LIKE '/static/img/%';

-- 订单行保存的是下单时的主图快照，同样需要修正，否则历史订单仍然空白。
UPDATE xy_order_item
SET cover_url = REPLACE(cover_url, '/static/img/', '/media/')
WHERE cover_url LIKE '/static/img/%';

SELECT product_id, product_name, cover_url FROM xy_product ORDER BY sort_order, product_id;
