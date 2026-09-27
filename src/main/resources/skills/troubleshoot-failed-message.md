---
name: troubleshoot-failed-message
description: Why did an iFlow fail, and how to fix it — for questions about a failed, retrying or escalated message.
---
1. Call getProblemMessages with the exact iFlow name (status FAILED,RETRY,ESCALATED; 24 hours unless the question says otherwise).
   No messages: call listIflows and check the name. If a close name exists, suggest it; never invent a failure.
2. Call getErrorDetails with the message id of the newest problem message. Use the real id from step 1, never a placeholder.
3. Call searchDocs with the key part of the error text (the exception name and a few words), to find the cause and the fix.
   If a passage points to the right page but not the part you need, call readPage for that page.
4. Answer in this order: what failed (iFlow, message id, time, status), the cause in one sentence, the fix as steps.
   Cite the pages the fix comes from. If the documentation does not cover the fix, say so.
