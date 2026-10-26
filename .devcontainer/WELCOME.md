# OrderManager in Codespaces

The app is being built and started for you (the first time takes a few minutes; progress is in the
**Terminal** panel). When it is ready, it opens in a new browser tab. If it does not, open the **Ports**
tab and click the globe icon next to port **8080**.

**Log in:** `admin` / `reviewer-demo-2026`

The demo data (50 SKUs, 1,000 orders in every status) is created on first start. Things to try are in the
README's *5-minute demo script*.

Useful commands (in the Terminal):

```bash
docker compose logs -f app      # application log
scripts/test.sh                 # run the 209 tests (about 5 minutes)
bash .devcontainer/start.sh     # start the app again after the codespace was stopped
```

This codespace and its port are private to you. The secrets in `.env` were generated for this codespace only.
