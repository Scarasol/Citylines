Citylines
=========

Minecraft 1.20.1 / Forge 47.4.0 / Java 17
Required: The Lost Cities 1.20-7.5.5
Optional runtime integrations: LC2H 4.2.4-LTS (+ Quantified API), Extraction Cities

Current handoff, configuration, interfaces and verification: docs/DELIVERY.md
Contributor constraints: docs/WORKFLOW.md

Build:
  gradlew.bat build --offline
  python tools/generate_road_parts.py --check

Create a NEW test world. Citylines is enabled by default; no terrain-mode selection.
Existing experimental worlds and run/ configuration are not migrated or deleted.
