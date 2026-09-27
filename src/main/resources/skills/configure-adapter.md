---
name: configure-adapter
description: How to configure an adapter or channel (JDBC, SFTP, AS4, Kafka, HTTP, OData, ...) — the steps and the parameters.
---
1. Check which adapter and direction is meant. Sending messages to a partner uses the receiver adapter;
   receiving from a partner uses the sender adapter.
2. Use the passages you were given. If they come from the right page but miss the parameter table,
   call readPage for that page (part 1, then part 2 if the table continues).
3. Answer with: prerequisites (credentials, keystore entries, Cloud Connector, drivers), then the channel
   settings tab by tab, naming each parameter exactly as the page does, with its purpose.
4. Cite every page you used. Do not add parameters the pages do not name.
