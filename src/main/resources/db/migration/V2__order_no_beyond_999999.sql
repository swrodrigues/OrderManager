-- lpad(n, 6) truncates longer numbers (1000000 -> '100000'), which would collide with an existing order
-- number and make every later insert fail. Pad to at least 6 digits instead, never truncate.
CREATE FUNCTION next_order_no() RETURNS varchar
    LANGUAGE sql
AS $$
    SELECT 'ORD-' || lpad(n::text, greatest(6, length(n::text)), '0')
    FROM (SELECT nextval('order_no_seq') AS n) s
$$;

ALTER TABLE customer_order ALTER COLUMN order_no SET DEFAULT next_order_no();
