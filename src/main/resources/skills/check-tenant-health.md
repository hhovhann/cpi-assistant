---
name: check-tenant-health
description: What is not running or failing on the tenant right now — for questions about the tenant as a whole.
---
1. Call listIflows. Every iFlow whose status is not STARTED is a finding (ERROR, STOPPED, ...).
2. Call getProblemMessages without an iFlow name (status FAILED,RETRY,ESCALATED, 24 hours) for failing messages.
3. For the most recent problem, call getErrorDetails to name the cause.
4. Answer as a short list: iFlow, what is wrong, since when. Say plainly if everything is running.
