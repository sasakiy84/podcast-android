-- Frozen schema from v0.2.0; do not derive from the current PodcastStore.
CREATE TABLE podcasts (feed TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, artwork TEXT NOT NULL, description TEXT NOT NULL);
CREATE TABLE episodes (id TEXT PRIMARY KEY, feed TEXT NOT NULL, title TEXT NOT NULL, audio TEXT NOT NULL, description TEXT NOT NULL, published INTEGER NOT NULL, duration INTEGER NOT NULL, artwork TEXT NOT NULL);
CREATE INDEX episodes_feed ON episodes(feed);
CREATE TABLE progress (id TEXT PRIMARY KEY, position INTEGER NOT NULL, duration INTEGER NOT NULL, completed INTEGER NOT NULL);
CREATE TABLE downloads (id TEXT PRIMARY KEY, manager_id INTEGER NOT NULL);
INSERT INTO podcasts (feed,title,author,artwork,description) VALUES ('https://example.org/rss','番組','配信者','','説明');
INSERT INTO episodes (id,feed,title,audio,description,published,duration,artwork) VALUES ('one','https://example.org/rss','回','https://example.org/one.mp3','本文',1,1000,'');
INSERT INTO progress VALUES ('one',500,1000,0);
INSERT INTO downloads VALUES ('one',42);
