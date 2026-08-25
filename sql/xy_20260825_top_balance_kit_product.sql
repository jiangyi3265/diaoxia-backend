-- 上架正式商品：TOP技研 快捷天平套组。
-- 可重复执行。首次创建库存为 1；重复执行时保留实时库存，避免售出后被脚本重置。

SET @xy_product_name = 'TOP技研 快捷天平套组';

INSERT INTO xy_product(
  product_name,
  category_name,
  cover_url,
  detail_text,
  sale_price,
  member_discount_enabled,
  stock,
  status,
  sort_order
)
SELECT
  @xy_product_name,
  '钓虾装备',
  '/media/top-quick-balance-kit.jpg',
  '整套为钓虾新手成品套装，到手简单组装即可直接下竿，不用自己绑复杂线组，适合俱乐部新手体验和入门练手。

套装内含：
1. 0.75g 高碳钢天平 ×2
高碳钢材质，弹性好，可撑开双钩并分开子线，减少缠绕；0.75g 配重适配钓虾池水深，信号灵敏。

2. 2.8 分阿波母线组 ×1
完整主线上带阿波浮标，可清晰放大虾咬饵信号，方便新手判断抓口时机。

3. 不锈钢天平水深棒 ×1
用于测量池水深度和快速找底，方便调整到合适虾层。

4. 进口精灵环天平钩 ×10 枚
成品子线钩采用快拆设计，打结或损坏后可直接更换子线。',
  198.00,
  0,
  1,
  '0',
  1
WHERE NOT EXISTS (
  SELECT 1 FROM xy_product WHERE product_name = @xy_product_name
);

UPDATE xy_product
SET category_name = '钓虾装备',
    cover_url = '/media/top-quick-balance-kit.jpg',
    detail_text = '整套为钓虾新手成品套装，到手简单组装即可直接下竿，不用自己绑复杂线组，适合俱乐部新手体验和入门练手。

套装内含：
1. 0.75g 高碳钢天平 ×2
高碳钢材质，弹性好，可撑开双钩并分开子线，减少缠绕；0.75g 配重适配钓虾池水深，信号灵敏。

2. 2.8 分阿波母线组 ×1
完整主线上带阿波浮标，可清晰放大虾咬饵信号，方便新手判断抓口时机。

3. 不锈钢天平水深棒 ×1
用于测量池水深度和快速找底，方便调整到合适虾层。

4. 进口精灵环天平钩 ×10 枚
成品子线钩采用快拆设计，打结或损坏后可直接更换子线。',
    sale_price = 198.00,
    member_discount_enabled = 0,
    status = '0',
    sort_order = 1
WHERE product_name = @xy_product_name;

SELECT product_id,
       product_name,
       category_name,
       sale_price,
       member_discount_enabled,
       stock,
       status,
       cover_url
FROM xy_product
WHERE product_name = @xy_product_name;
