package com.reetro.lostinthesky.ship;

import com.reetro.lostinthesky.LostInTheSky;
import com.reetro.lostinthesky.client.ShipBrainLinkClient;
import com.reetro.lostinthesky.network.BrainLinkModePayload;
import com.reetro.lostinthesky.network.BrainToggleLinkPayload;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registrations for AI airships: the ship brain block, its block entity, menu, creative tab and link packets. */
public final class ShipBrains {
    /** Any wrench (Create's is in this tag). */
    public static final TagKey<net.minecraft.world.item.Item> WRENCHES =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));
    /** How far (squared, blocks) a player may be from a block they link. */
    private static final double LINK_REACH_SQR = 10.0 * 10.0;

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(LostInTheSky.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LostInTheSky.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LostInTheSky.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, LostInTheSky.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LostInTheSky.MODID);

    public static final DeferredBlock<ShipBrainBlock> SHIP_BRAIN = BLOCKS.register("ship_brain",
            () -> new ShipBrainBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PINK)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> SHIP_BRAIN_ITEM = ITEMS.registerSimpleBlockItem(SHIP_BRAIN, new Item.Properties());

    @SuppressWarnings("DataFlowIssue") // the data fixer type is unused for mod block entities
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShipBrainBlockEntity>> SHIP_BRAIN_BE =
            BLOCK_ENTITIES.register("ship_brain",
                    () -> BlockEntityType.Builder.of(ShipBrainBlockEntity::new, SHIP_BRAIN.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<ShipBrainMenu>> SHIP_BRAIN_MENU =
            MENUS.register("ship_brain", () -> IMenuTypeExtension.create(ShipBrainMenu::new));

    /** The mod's creative tab. Add new items to {@code displayItems}. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.lost_in_the_sky"))
                    .icon(() -> SHIP_BRAIN_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(SHIP_BRAIN_ITEM.get()))
                    .build());

    private ShipBrains() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        TABS.register(modEventBus);
        modEventBus.addListener(ShipBrains::onRegisterPayloads);
    }

    public static boolean isWrench(ItemStack stack) {
        return stack.is(WRENCHES);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(BrainLinkModePayload.TYPE, BrainLinkModePayload.STREAM_CODEC,
                // Only ever runs on the client; the class reference is resolved lazily.
                (payload, context) -> ShipBrainLinkClient.handle(payload));
        registrar.playToServer(BrainToggleLinkPayload.TYPE, BrainToggleLinkPayload.STREAM_CODEC, ShipBrains::onToggleLink);
    }

    /** A player in link mode wrench-clicked a block: link or unlink it, after checking they really can. */
    private static void onToggleLink(BrainToggleLinkPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !isWrench(player.getMainHandItem())) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos target = payload.target();
        if (target.equals(payload.brain())
                || !(level.getBlockEntity(payload.brain()) instanceof ShipBrainBlockEntity brain)
                || !brain.isOnShip()
                || Sable.HELPER.distanceSquaredWithSubLevels(level, player.getEyePosition(), Vec3.atCenterOf(target)) > LINK_REACH_SQR
                || !brain.links().contains(target) && !brain.isLinkCandidate(target)) {
            return;
        }
        boolean wasLinked = brain.links().contains(target);
        boolean linked = brain.toggleLink(target);
        String key = linked ? "linked" : wasLinked ? "unlinked" : "full";
        player.displayClientMessage(Component.translatable(
                "message.lost_in_the_sky.ship_brain." + key, brain.links().size()), true);
    }
}
