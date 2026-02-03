PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS user_packages (
  user_id INTEGER NOT NULL,
  package_id INTEGER NOT NULL,
  PRIMARY KEY (user_id, package_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (package_id) REFERENCES packages(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_packages_user ON user_packages(user_id);
CREATE INDEX IF NOT EXISTS idx_user_packages_package ON user_packages(package_id);
