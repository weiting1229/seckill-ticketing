BEGIN;

CREATE TEMP TABLE tgt AS
  SELECT id FROM events
  WHERE title LIKE 'LoadTest Scenario%'
     OR title LIKE '%ZUTOMAYO%'
     OR title LIKE '%落日飛車%'
     OR title ILIKE '%sunset rollercoaster%';

CREATE TEMP TABLE tgt_tt AS
  SELECT id FROM ticket_types WHERE event_id IN (SELECT id FROM tgt);

-- 順序不可調換:ticket_types → events 有外鍵(NO ACTION),先刪 events 會被擋下。
-- orders / stock_logs 對 ticket_types **沒有**外鍵,所以不先刪它們不會報錯 ——
-- 只會留下指向不存在票種的孤兒列,而訂單列表頁會因此壞掉且沒有任何一步報錯。
DELETE FROM orders       WHERE ticket_type_id IN (SELECT id FROM tgt_tt);
DELETE FROM stock_logs   WHERE ticket_type_id IN (SELECT id FROM tgt_tt);
DELETE FROM ticket_types WHERE id IN (SELECT id FROM tgt_tt);
DELETE FROM events       WHERE id IN (SELECT id FROM tgt);

COMMIT;
