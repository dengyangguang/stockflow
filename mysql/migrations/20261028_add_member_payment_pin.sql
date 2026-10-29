CREATE TABLE IF NOT EXISTS bus_member_payment_pin (
  member_id int NOT NULL PRIMARY KEY,
  pin_hash varchar(255) NOT NULL,
  failed_attempts int NOT NULL DEFAULT 0,
  locked_until datetime DEFAULT NULL,
  CONSTRAINT fk_member_payment_pin_member FOREIGN KEY (member_id) REFERENCES bus_member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
