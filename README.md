# ItemNameLab

`ItemNameLab` is a small Bukkit/Purpur test plugin for comparing how different item-name APIs behave on the same item.

It uses a single command:

```text
/itemnamelab <generated|hand> [all|display|item|customname|localized|i18n|effective|effectivefallback|translation|translated|typetranslation|material|cascade]
```

Examples:

```text
/itemnamelab generated
/itemnamelab generated customname
/itemnamelab generated effective
/itemnamelab hand translated
/itemnamelab hand effective
/itemnamelab hand cascade
```

What it does:

- `generated` creates a sample set of items automatically and adds them to your inventory
- `hand` inspects only the item you are currently holding
- methods that are missing on Spigot are wrapped in try/catch and report the exception in chat
- `customname` inspects the Purpur/Paper `ItemMeta#customName()` path directly
- `translated` sends a translatable chat component so the client renders the localized item name itself
- `effectivefallback` and `cascade` intentionally demonstrate a fallback path after an exception

The repository uses the same Maven and GitHub Actions setup pattern as `PrettySimpleShop`, including release builds that attach the built jar to the published GitHub release.
