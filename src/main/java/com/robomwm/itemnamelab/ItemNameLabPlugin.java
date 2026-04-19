package com.robomwm.itemnamelab;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class ItemNameLabPlugin extends JavaPlugin
{
    @Override
    public void onEnable()
    {
        PluginCommand command = getCommand("itemnamelab");
        if (command == null)
        {
            getLogger().severe("itemnamelab command is missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ItemNameLabCommand executor = new ItemNameLabCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
