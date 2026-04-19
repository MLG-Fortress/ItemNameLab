package com.robomwm.itemnamelab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
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
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        if (args.length == 0)
        {
            sendUsage(player, label);
            return true;
        }

        ItemSource source = ItemSource.fromArgument(args[0]);
        if (source == null)
        {
            sendPlain(player, "Unknown source '" + args[0] + "'.");
            sendUsage(player, label);
            return true;
        }

        NameMethod method = args.length > 1 ? NameMethod.fromArgument(args[1]) : NameMethod.ALL;
        if (method == null)
        {
            sendPlain(player, "Unknown method '" + args[1] + "'.");
            sendUsage(player, label);
            return true;
        }

        List<SampleItem> items;
        if (source == ItemSource.HAND)
        {
            ItemStack inHand = player.getInventory().getItemInMainHand();
            if (inHand == null || inHand.getType().isAir())
            {
                sendPlain(player, "Hold an item first, or use /" + label + " generated " + method.argument + ".");
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
            return partialMatches(args[0], ItemSource.arguments());
        if (args.length == 2)
            return partialMatches(args[1], NameMethod.arguments());
        return Collections.emptyList();
    }

    private void sendUsage(Player player, String label)
    {
        sendPlain(player, "[ItemNameLab] /" + label + " <" + String.join("|", ItemSource.arguments()) + "> [" + String.join("|", NameMethod.arguments()) + "]");
        sendPlain(player, "generated: create sample items and inspect them");
        sendPlain(player, "hand: inspect only the item in your main hand");
        sendPlain(player, "omit the method to inspect all available name methods");
    }

    private void giveItems(Player player, List<SampleItem> items)
    {
        ItemStack[] generatedItems = items.stream()
                .map(sample -> sample.item.clone())
                .toArray(ItemStack[]::new);

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(generatedItems);
        for (ItemStack item : overflow.values())
            player.getWorld().dropItemNaturally(player.getLocation(), item);

        sendPlain(player, "[ItemNameLab] Generated " + items.size() + " sample items.");
        if (!overflow.isEmpty())
            sendPlain(player, "[ItemNameLab] Inventory was full, so overflow items were dropped at your feet.");
    }

    private void inspectItems(Player player, NameMethod selectedMethod, List<SampleItem> items, ItemSource source)
    {
        List<NameMethod> methods = selectedMethod == NameMethod.ALL ? NameMethod.inspectableMethods() : Collections.singletonList(selectedMethod);
        String methodDescription = selectedMethod == NameMethod.ALL ? "all methods" : selectedMethod.signature;
        sendPlain(player, "[ItemNameLab] Inspecting " + items.size() + " item(s) using " + methodDescription + " from " + source.argument + ".");

        for (SampleItem sample : items)
        {
            sendPlain(player, "[ItemNameLab] " + sample.label
                    + " (" + sample.item.getType() + ")"
                    + " - " + sample.description);

            for (NameMethod method : methods)
            {
                NameResult result = resolveName(sample.item, method);
                sendValue(player, "  " + method.signature + " -> ", result.value);

                if (result.exception != null)
                {
                    sendPlain(player, "    hit exception: "
                            + result.exception.getClass().getSimpleName()
                            + (result.exception.getMessage() == null ? "" : " - " + result.exception.getMessage()));
                }

                if (result.usedFallback)
                    sendPlain(player, "    fallback used after exception");
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
                    public NameResult get() throws Throwable
                    {
                        ItemMeta meta = item.getItemMeta();
                        return NameResult.success(legacyComponents(meta != null && meta.hasDisplayName() ? meta.getDisplayName() : "<none>"));
                    }
                });
            case ITEM:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public NameResult get() throws Throwable
                    {
                        ItemMeta meta = item.getItemMeta();
                        return NameResult.success(legacyComponents(meta != null && meta.hasItemName() ? meta.getItemName() : "<none>"));
                    }
                });
            case CUSTOM_NAME:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public NameResult get() throws Throwable
                    {
                        return NameResult.success(describeCustomName(item));
                    }
                });
            case LOCALIZED:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public NameResult get() throws Throwable
                    {
                        ItemMeta meta = item.getItemMeta();
                        return NameResult.success(legacyComponents(meta != null && meta.hasLocalizedName() ? meta.getLocalizedName() : "<none>"));
                    }
                });
            case I18N:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public NameResult get() throws Throwable
                    {
                        return NameResult.success(legacyComponents(item.getI18NDisplayName()));
                    }
                });
            case EFFECTIVE:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public NameResult get() throws Throwable
                    {
                        return NameResult.success(describeEffectiveName(item));
                    }
                });
            case EFFECTIVE_FALLBACK:
                try
                {
                    return NameResult.success(describeEffectiveName(item));
                }
                catch (Throwable throwable)
                {
                    return NameResult.fallback(plainComponents(rawMaterialName(item)), throwable);
                }
            case TRANSLATION_KEY:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public NameResult get() throws Throwable
                    {
                        return NameResult.success(plainComponents(item.getTranslationKey()));
                    }
                });
            case TRANSLATED:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public NameResult get() throws Throwable
                    {
                        return NameResult.success(translatedComponents(item.getTranslationKey()));
                    }
                });
            case TYPE_TRANSLATION_KEY:
                return attempt(new ValueSupplier()
                {
                    @Override
                    public NameResult get() throws Throwable
                    {
                        return NameResult.success(plainComponents(item.getType().getTranslationKey()));
                    }
                });
            case MATERIAL:
                return NameResult.success(plainComponents(rawMaterialName(item)));
            case CASCADE:
                ItemMeta meta = item.getItemMeta();
                if (meta != null && meta.hasDisplayName())
                    return NameResult.success(legacyComponents(meta.getDisplayName()));
                if (meta != null && meta.hasItemName())
                    return NameResult.success(legacyComponents(meta.getItemName()));
                try
                {
                    return NameResult.success(describeEffectiveName(item));
                }
                catch (Throwable throwable)
                {
                    return NameResult.fallback(plainComponents(rawMaterialName(item)), throwable);
                }
            case ALL:
            default:
                return NameResult.success(plainComponents("<unsupported>"));
        }
    }

    private NameResult attempt(ValueSupplier supplier)
    {
        try
        {
            return supplier.get();
        }
        catch (Throwable throwable)
        {
            return NameResult.failure(throwable);
        }
    }

    private BaseComponent[] describeEffectiveName(ItemStack item) throws Throwable
    {
        Object component = item.effectiveName();
        return serializeAdventureComponent(component);
    }

    private BaseComponent[] describeCustomName(ItemStack item) throws Throwable
    {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasCustomName())
            return plainComponents("<none>");

        Object component = meta.customName();
        if (component == null)
            return plainComponents("<null>");
        return serializeAdventureComponent(component);
    }

    private BaseComponent[] serializeAdventureComponent(Object component) throws Throwable
    {
        Class<?> serializerClass = Class.forName("net.kyori.adventure.text.serializer.gson.GsonComponentSerializer");
        Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
        Method gsonMethod = serializerClass.getMethod("gson");
        Object serializer = gsonMethod.invoke(null);
        Method serializeMethod = serializerClass.getMethod("serialize", componentClass);
        String serialized = String.valueOf(serializeMethod.invoke(serializer, component));
        Class<?> componentSerializerClass = Class.forName("net.md_5.bungee.chat.ComponentSerializer");
        Method parseMethod = componentSerializerClass.getMethod("parse", String.class);
        return (BaseComponent[]) parseMethod.invoke(null, serialized);
    }

    private List<SampleItem> createSampleItems()
    {
        List<SampleItem> items = new ArrayList<SampleItem>();
        items.add(new SampleItem("plain-diamond-sword", new ItemStack(Material.DIAMOND_SWORD), "No custom name metadata"));
        items.add(new SampleItem("display-name-paper", createDisplayNameItem(), "Only setDisplayName(...)"));
        items.add(new SampleItem("item-name-book", createItemNameItem(), "Only setItemName(...)"));
        items.add(new SampleItem("custom-name-name-tag", createCustomNameItem(), "Only customName(Component)"));
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

    private ItemStack createCustomNameItem()
    {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        try
        {
            meta.customName(Component.text("Custom Name Only", NamedTextColor.BLUE));
            meta.setLore(Arrays.asList(ChatColor.GRAY + "Sample: custom-name"));
        }
        catch (Throwable throwable)
        {
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Sample: custom-name unavailable",
                    ChatColor.DARK_GRAY + throwable.getClass().getSimpleName()));
        }
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

    private String rawMaterialName(ItemStack item)
    {
        return item.getType().name();
    }

    private void sendPlain(Player player, String message)
    {
        sendValue(player, "", plainComponents(message));
    }

    private void sendValue(Player player, String prefix, BaseComponent[] value)
    {
        TextComponent message = new TextComponent(prefix);
        if (value != null)
        {
            for (BaseComponent component : value)
                message.addExtra(component);
        }
        player.spigot().sendMessage(message);
    }

    private BaseComponent[] plainComponents(String value)
    {
        return new BaseComponent[] { new TextComponent(normalize(value)) };
    }

    private BaseComponent[] translatedComponents(String translationKey)
    {
        if (translationKey == null || translationKey.isEmpty())
            return plainComponents(normalize(translationKey));
        return new BaseComponent[] { new TranslatableComponent(translationKey) };
    }

    private BaseComponent[] legacyComponents(String value)
    {
        return TextComponent.fromLegacyText(normalize(value));
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
        NameResult get() throws Throwable;
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
        private final BaseComponent[] value;
        private final Throwable exception;
        private final boolean usedFallback;

        private NameResult(BaseComponent[] value, Throwable exception, boolean usedFallback)
        {
            this.value = value;
            this.exception = exception;
            this.usedFallback = usedFallback;
        }

        private static NameResult success(BaseComponent[] value)
        {
            return new NameResult(value, null, false);
        }

        private static NameResult failure(Throwable throwable)
        {
            return new NameResult(new BaseComponent[] { new TextComponent("<exception>") }, throwable, false);
        }

        private static NameResult fallback(BaseComponent[] value, Throwable throwable)
        {
            return new NameResult(value, throwable, true);
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
        ALL("all", "all methods"),
        DISPLAY("display", "ItemMeta#getDisplayName()"),
        ITEM("item", "ItemMeta#getItemName()"),
        CUSTOM_NAME("customname", "ItemMeta#customName()"),
        LOCALIZED("localized", "ItemMeta#getLocalizedName()"),
        I18N("i18n", "ItemStack#getI18NDisplayName()"),
        EFFECTIVE("effective", "ItemStack#effectiveName()"),
        EFFECTIVE_FALLBACK("effectivefallback", "ItemStack#effectiveName() with fallback"),
        TRANSLATION_KEY("translation", "ItemStack#getTranslationKey()"),
        TRANSLATED("translated", "TranslatableComponent(ItemStack#getTranslationKey())"),
        TYPE_TRANSLATION_KEY("typetranslation", "Material#getTranslationKey()"),
        MATERIAL("material", "Material#name()"),
        CASCADE("cascade", "display -> item -> ItemStack#effectiveName() -> Material#name()");

        private final String argument;
        private final String signature;

        NameMethod(String argument, String signature)
        {
            this.argument = argument;
            this.signature = signature;
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
