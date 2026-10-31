# Cinnamon Rush

A singleplayer, browser-based cinnamon roll game set in an industrial kitchen. Big orders come in, and you
run the line (mix → proof → roll & fill → bake → ice → box) to fill as much as you can before the
delivery truck arrives.

The game logic runs in a small Java server (no external libraries); the browser renders the kitchen.

## Run it

Requires Java 17 or newer.

- Windows: `run.bat`
- macOS / Linux: `./run.sh`

Then open http://localhost:8080.

## Controls

- Click stations to start batches, pick up trays, and put them down.
- Click an order on the board to box the rolls you're holding.
- `Space` pause · `X` trash · `1`–`4` pick a filling or icing
