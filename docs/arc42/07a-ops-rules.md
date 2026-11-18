# 7a. Ops rules

The six rules for operating the production host by hand. Each was bought by an incident and carries
what it cost. They are cited by number across the repository (compose files, the Caddyfile, the
Keycloak theme notes, ADRs, code comments); this page is what those citations resolve to.
[Chapter 7](07-deployment-view.md) shows the rules applied to this deployment, and
[`ops/README.md`](https://github.com/Keglev/maintenance-assistant/blob/main/ops/README.md)
lists the files on the host they apply to.

The wording of rules 1-5 is the wording they were written in — copied, not restated, because a rule
reworded is a rule re-decided, and these were decided by incidents rather than by editing. Rule 6 is
numbered 6 because rules 4 and 5 were written the same day.

- **OPS RULE 1** (learned the hard way, cost ~12 min): the deploy pipeline
  pulls IMAGES ONLY — it never syncs docker-compose.prod.yml or
  .env.prod to the host. Any merged change to those files must be
  applied on the server BY HAND before/with the next deploy (#22 was
  merged in git but absent on the host until an ops session copied
  it; rollback copy at docker-compose.prod.yml.bak-20260807-162246).
- **OPS RULE 2:** an .env.prod edit is INERT until the container is
  recreated (`up -d --force-recreate backend`); env freezes at
  container creation. A container once ran 150 calls into a 401 with
  a stale key while the file was already correct.
- **OPS RULE 3** (learned 2026-08-08, cost one failed deploy round): the
  prod compose bind-mounts the Caddyfile as a SINGLE FILE
  (./caddy/Caddyfile:/etc/caddy/Caddyfile:ro), and a single-file bind
  mount tracks the file's INODE, not its path. Replacing the host file
  with `mv` gives it a NEW inode, and the container keeps reading the
  OLD one. Nothing reports this: `caddy validate` and `caddy reload`
  run INSIDE the container and both happily operate on the stale
  content, and the reload reports success. Measured: a grep inside the
  container found 0 CSP lines while the host file had 2. REMEDY: `cp`
  OVER the existing file (same inode), or
  `up -d --force-recreate caddy`. Never `mv`. The same trap applies to
  any single-file bind mount — frontend-config.json is the other one
  in this stack.

  APPLIED TO THIS STACK — the four single-file mounts, rows 3, 4, 5 and 7 of the table in
  *Files the pipeline never deploys* in
  [`ops/README.md`](https://github.com/Keglev/maintenance-assistant/blob/main/ops/README.md).
  A single-file bind mount tracks the file's INODE, not its path: replacing the host file with `mv` gives it a new inode and the container keeps
  reading the old one, silently — `caddy validate` and `caddy reload` run INSIDE the container
  and both happily report success against stale content. **Always `cp` OVER the existing file**,
  or `up -d --force-recreate <service>`. Never `mv`. The theme (row 6) is a directory mount and
  does not carry the trap, though theme caching means it still needs a force-recreate to take
  effect.
- **OPS RULE 4** (learned 2026-08-27, cost one root console mid-deploy):
  BEFORE A HAND-DEPLOY, `ls -l` THE TARGET. The deploy account has no
  sudo, so a root-owned file on the host CANNOT be replaced from it, and
  the failure arrives halfway through a session rather than at its
  start. docker-compose.prod.yml was found root:root 644, sixteen days
  after a root session created it and did not hand it over — invisible
  until a deploy finally needed to write that file. A file that is not
  deploy:deploy needs the root console, and gets `chown deploy:deploy`
  while you are there.
- **OPS RULE 5, THE SHARED-HOST RULE** (2026-08-27): ROOT CREATES AND HANDS
  OVER, deploy OPERATES. /opt is root-owned, and `deploy` is not in
  sudoers at all — ops/host/provision.sh builds it that way on purpose
  (adduser --disabled-password, passwd -l, docker group, no sudoers
  entry). So a second project's directory needs exactly one root action
  to exist, plus a chown, and everything after that is deploy's. This is
  the design working rather than a gap: the account that runs containers
  every day is not the account that can rewrite the machine.
  NOTE THAT docker GROUP MEMBERSHIP IS ROOT-EQUIVALENT in practice, and
  the rule stands anyway — a boundary that is trivially crossable is
  still the written statement of who is supposed to do what, and using
  the docker socket to sidestep sudoers is how an operator surprises the
  next one.

**OPS RULE 6 — A BACKUP IS NAMED FOR ITS CONTENT DATE, NOT FOR THE DAY IT WAS COPIED.**

```bash
f=docker-compose.prod.yml; cp -pn "$f" "$f.bak.of-$(date -r "$f" +%F)"
```

The name is `<file>.bak.of-<YYYY-MM-DD>`, and the date comes from the SOURCE FILE'S mtime
(`date -r FILE +%F`) — never from today. `cp -p` preserves that mtime onto the copy, so the name
and the timestamp on disk agree and a later `ls -l` corroborates the filename instead of
contradicting it. Two backups of the same content taken on different days collide by design;
`cp -n` refuses the second, which is the right answer, because they are the same bytes.

**Why, measured 2026-08-27.** The host held `.env.prod.bak-2026-08-27`,
`docker-compose.prod.yml.bak-2026-08-27` and `Caddyfile.bak-2026-08-27` whose contents were from
**2026-08-19, 2026-08-11 and 2026-08-08**. Every one of them was named for the day somebody ran
`cp`, and every one of them was wrong about what it contained. **A backup named for the wrong day is
restored with confidence**, which is worse than no backup at all — the operator does not stop to
check, because the filename already answered the question.

[Back to chapter 7, Deployment View](07-deployment-view.md)
