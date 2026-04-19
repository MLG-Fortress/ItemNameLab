package com.robomwm.itemnamelab;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class ItemNameLabCommand implements CommandExecutor, TabCompleter
{
    private final JavaPlugin plugin;

    public ItemNameLabCommand(JavaPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        if (!(sender instanceof Player))
        {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        if (args.length == 0)
        {
            sendUsage(player, label);
            return true;
        }

        NameMethod method = NameMethod.fromArgument(args[0]);
        if (method == null)
        {
            player.sendMessage(ChatColor.RED + "Unknown method '" + args[0] + "'.");
            sendUsage(player, label);
            return true;
        }

        ItemSource source = args.length > 1 ? ItemSource.fromArgument(args[1]) : ItemSource.GENERATED;
        if (source == null)
        {
            player.sendMessage(ChatColor.RED + "Unknown source '" + args[1] + "'.");
            sendUsage(player, label);
            return true;
        }

        List<SampleItem> items;
        if (source == ItemSource.HAND)
        {
            ItemStack inHand = player.getInventory().getItemInMainHand();
            if (inHand == null || inHand.getType().isAir())
            {
                player.sendMessage(ChatColor.RED + "Hold an item first, or use /" + label + " " + method.argument + " generated.");
                return true;
            }

            items = Collections.singletonList(new SampleItem("main-hand", inHand.clone(), "Your currently held item"));
        }
        else
        {
            items = createSampleItems();
            giveItems(player, items);
        }

        inspectItems(player, method, items, source);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args)
    {
        if (args.length == 1)
            return partialMatches(args[0], NameMethod.arguments());
        if (args.length == 2)
            return partialMatches(args[1], ItemSource.arguments());
        return Collections.emptyList();
    }

    private void sendUsage(Player player, String label)
    {
        player.sendMessage(ChatColor.GOLD + "[ItemNameLab] " + ChatColor.YELLOW
                + "/" + label + " <" + String.join("|", NameMethod.arguments()) + "> [generated|hand]");
        player.sendMessage(ChatColor.GRAY + "generated: create sample items and inspect them");
        player.sendMessage(ChatColor.GRAY + "hand: inspect only the item in your main hand");
    }

    private void giveItems(Player player, List<SampleItem> items)
    {
        ItemStack[] generatedItems = items.stream()
                .map(sample -> sample.item.clone())
                .toArray(ItemStack[]::new);

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(generatedItems);
        for (ItemStack item : overflow.values())
            player.getWorld().dropItemNaturally(player.getLocation(), item);

        player.sendMessage(ChatColor.GOLD + "[ItemNameLab] " + ChatColor.YELLOW
                + "Generated " + items.size() + " sample items.");
        if (!overflow.isEmpty())
            player.sendMessage(ChatColor.RED + "[ItemNameLab] Inventory was full, so overflow items were dropped at your feet.");
    }

    private void inspectItems(Player player, NameMethod selectedMethod, List<SampleItem> items, ItemSource source)
    {
        List<NameMethod> methods = selectedMethod == NameMethod.ALL ? NameMethod.inspectableMethods() : Collections.singletonList(selectedMethod);
        player.sendMessage(ChatColor.AQUA + "[ItemNameLab] "
                + ChatColor.WHITE + "Inspecting " + items.size() + " item(s) using "
                + ChatColor.YELLOW + selectedMethod.argument
                + ChatColor.WHITE + " from " + ChatColor.YELLOW + source.argument + ChatColor.WHITE + ".");

        for (SampleItem sample : items)
        {
            player.sendMessage(ChatColor.GOLD + "[ItemNameLab] " + ChatColor.YELLOW + sample.label
                    + ChatColor.GRAY + " (" + sample.item.getType() + ")"
                    + ChatColor.DARK_GRAY + " - " + sample.description);

            for (NameMethod method : methods)
            {
                NameResult result = resolveName(sample.item, method);
                player.sendMessage(ChatColor.WHITE + "  " + ChatColor.YELLOW + method.argument
                        + ChatColor.WHITE + " -> " + ChatColor.GREEN + result.value);

                if (result.exception != null)
                {
                    player.sendMessage(ChatColor.RED + "    hit exception: "
                            + result.exception.getClass().getSimpleName()
                            + (result.exception.getMessage() == null ? "" : " - " + result.exception.getMessage()));
                }

                if (result.usedFallback)
                    player.sendMessage(ChatColor.GRAY + "    fallback used after exception");
            }
        }
    }

    private NameResult resolveName(ItemStack item, NameMethod method)
    {
        switch (method)
        {
            case DISPLAY:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public String get() throws Throwable
                    {
                        ItemMeta meta = item.getItemMeta();
                        return meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "<none>";
                    }
                });
            case ITEM:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public String get() throws Throwable
                    {
                        ItemMeta meta = item.getItemMeta();
                        return meta != null && meta.hasItemName() ? meta.getItemName() : "<none>";
                    }
                });
            case LOCALIZED:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public String get() throws Throwable
                    {
                        ItemMeta meta = item.getItemMeta();
                        return meta != null && meta.hasLocalizedName() ? meta.getLocalizedName() : "<none>";
                    }
                });
            case I18N:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public String get() throws Throwable
                    {
                        return item.getI18NDisplayName();
                    }
                });
            case EFFECTIVE:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public String get() throws Throwable
                    {
                        return describeEffectiveName(item);
                    }
                });
            case EFFECTIVE_FALLBACK:
                try
                {
                    return NameResult.success(describeEffectiveName(item));
                }
                catch (Throwable throwable)
                {
                    return NameResult.fallback(prettyMaterialName(item), throwable);
                }
            case TRANSLATION_KEY:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public String get() throws Throwable
                    {
                        return item.getTranslationKey();
                    }
                });
            case TYPE_TRANSLATION_KEY:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public String get() throws Throwable
                    {
                        return item.getType().getTranslationKey();
                    }
                });
            case MATERIAL:
                return NameResult.success(prettyMaterialName(item));
            case CASCADE:
                ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName())
                    return NameResult.success(meta.getDisplayName());
                if (meta != null && meta.hasItemName())
                    return NameResult.success(meta.getItemName());
                try
                {
                    return NameResult.success(describeEffectiveName(item));
                }
                catch (Throwable throwable)
                {
                    return NameResult.fallback(prettyMaterialName(item), throwable);
                }
            case ALL:
            default:
                return NameResult.success("<unsupported>");
        }
    }

    private NameResult attempt(ValueSupplier supplier)
    {
        try
        {
            return NameResult.success(supplier.get());
        }
        catch (Throwable throwable)
        {
            return NameResult.failure(throwable);
        }
    }

    private String describeEffectiveName(ItemStack item) throws Throwable
    {
        Object component = item.effectiveName();
        try
        {
            return serializeAdventureComponent(component);
        }
        catch (Throwable ignored)
        {
            return String.valueOf(component);
        }
    }

    private String serializeAdventureComponent(Object component) throws Throwable
    {
        Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
        Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
        Method plainTextMethod = serializerClass.getMethod("plainText");
        Object serializer = plainTextMethod.invoke(null);
        Method serializeMethod = serializerClass.getMethod("serialize", componentClass);
        Object serialized = serializeMethod.invoke(serializer, component);
        return normalize(String.valueOf(serialized));
    }

    private List<SampleItem> createSampleItems()
    {
        List<SampleItem> items = new ArrayList<SampleItem>();
        items.add(new SampleItem("plain-diamond-sword", new ItemStack(Material.DIAMOND_SWORD), "No custom name metadata"));
        items.add(new SampleItem("display-name-paper", createDisplayNameItem(), "Only setDisplayName(...)"));
        items.add(new SampleItem("item-name-book", createItemNameItem(), "Only setItemName(...)"));
        items.add(new SampleItem("both-named-shield", createBothNamedItem(), "Display name and item name are different"));
        items.add(new SampleItem("localized-compass", createLocalizedItem(), "Only setLocalizedName(...)"));
        items.add(new SampleItem("metadata-heavy-axe", createMetadataHeavyItem(), "Display name plus lore, enchant, flags, and model data"));
        return items;
    }

    private ItemStack createDisplayNameItem()
    {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Display Name Only");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Sample: display-name"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createItemNameItem()
    {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(ChatColor.AQUA + "Item Name Only");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Sample: item-name"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBothNamedItem()
    {
        ItemStack item = new ItemStack(Material.SHIELD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Display Name Wins");
        meta.setItemName(ChatColor.GREEN + "Hidden Item Name");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Sample: both-named"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createLocalizedItem()
    {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.setLocalizedName("itemnamelab.localized.compass");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Sample: localized-name"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createMetadataHeavyItem()
    {
        ItemStack item = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "Metadata Heavy Axe");
        meta.setItemName(ChatColor.YELLOW + "Metadata Internal Name");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Sample: metadata-heavy",
                ChatColor.DARK_GRAY + "Lore + enchant + flags + model data"));
        meta.setCustomModelData(Integer.valueOf(12345));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        return item;
    }

    private String prettyMaterialName(ItemStack item)
    {
        return item.getType().name().toLowerCase(Locale.ENGLISH).replace('_', ' ');
    }

    private String normalize(String value)
    {
        if (value == null)
            return "<null>";
        if (value.isEmpty())
            return "<empty>";
        return value;
    }

    private List<String> partialMatches(String token, List<String> choices)
    {
        String normalized = token.toLowerCase(Locale.ENGLISH);
        return choices.stream()
                .filter(choice -> choice.startsWith(normalized))
                .collect(Collectors.toList());
    }

    private interface ValueSupplier
    {
        String get() throws Throwable;
    }

    private static final class SampleItem
    {
        private final String label;
        private final ItemStack item;
        private final String description;

        private SampleItem(String label, ItemStack item, String description)
        {
            this.label = label;
            this.item = item;
            this.description = description;
        }
    }

    private static final class NameResult
    {
        private final String value;
        private final Throwable exception;
        private final boolean usedFallback;

        private NameResult(String value, Throwable exception, boolean usedFallback)
        {
            this.value = value;
            this.exception = exception;
            this.usedFallback = usedFallback;
        }

        private static NameResult success(String value)
        {
            return new NameResult(value == null ? "<null>" : value, null, false);
        }

        private static NameResult failure(Throwable throwable)
        {
            return new NameResult("<exception>", throwable, false);
        }

        private static NameResult fallback(String value, Throwable throwable)
        {
            return new NameResult(value == null ? "<null>" : value, throwable, true);
        }
    }

    private enum ItemSource
    {
        GENERATED("generated"),
        HAND("hand");

        private final String argument;

        ItemSource(String argument)
        {
            this.argument = argument;
        }

        private static ItemSource fromArgument(String value)
        {
            for (ItemSource source : values())
            {
                if (source.argument.equalsIgnoreCase(value))
                    return source;
            }
            return null;
        }

        private static List<String> arguments()
        {
            List<String> arguments = new ArrayList<String>();
            for (ItemSource source : values())
                arguments.add(source.argument);
            return arguments;
        }
    }

    private enum NameMethod
    {
        ALL("all"),
        DISPLAY("display"),
        ITEM("item"),
        LOCALIZED("localized"),
        I18N("i18n"),
        EFFECTIVE("effective"),
        EFFECTIVE_FALLBACK("effectivefallback"),
        TRANSLATION_KEY("translation"),
        TYPE_TRANSLATION_KEY("typetranslation"),
        MATERIAL("material"),
        CASCADE("cascade");

        private final String argument;

        NameMethod(String argument)
        {
            this.argument = argument;
        }

        private static NameMethod fromArgument(String value)
        {
            for (NameMethod method : values())
            {
                if (method.argument.equalsIgnoreCase(value))
                    return method;
            }
            return null;
        }

        private static List<String> arguments()
        {
            List<String> arguments = new ArrayList<String>();
            for (NameMethod method : values())
                arguments.add(method.argument);
            return arguments;
        }

        private static List<NameMethod> inspectableMethods()
        {
            List<NameMethod> methods = new ArrayList<NameMethod>();
            for (NameMethod method : values())
            {
                if (method != ALL)
                    methods.add(method);
            }
            return methods;
        }
    }
}
