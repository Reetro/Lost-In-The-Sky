package com.reetro.lostinthesky.client;

import com.reetro.lostinthesky.ship.ShipBrainBlockEntity;
import com.reetro.lostinthesky.ship.ShipBrainMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Ship brain orders: Follow me / Stay here / Off, the follow distance, how many thrusters are linked (link them with
 * a wrench) and whether the ship is locked. Drawn with plain fills, no texture.
 */
public class ShipBrainScreen extends AbstractContainerScreen<ShipBrainMenu> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 126;
    private static final int BACKGROUND = 0xE0202430;
    private static final int BORDER = 0xFF6A7FA8;
    private static final int TEXT = 0xFFE0E6F0;
    private static final int DIM_TEXT = 0xFF98A2B8;
    private static final int WARN_TEXT = 0xFFFFC65C;

    private Button followButton;
    private Button stayButton;
    private Button offButton;
    private Button distanceDown;
    private Button distanceUp;

    public ShipBrainScreen(ShipBrainMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos + 8;
        int y = topPos + 22;
        int w = WIDTH - 16;
        followButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.lost_in_the_sky.ship_brain.follow"),
                b -> click(ShipBrainBlockEntity.BUTTON_FOLLOW)).bounds(x, y, w, 20).build());
        distanceDown = addRenderableWidget(Button.builder(Component.literal("-"),
                b -> click(ShipBrainBlockEntity.BUTTON_DISTANCE_DOWN)).bounds(x, y + 24, 20, 20).build());
        distanceUp = addRenderableWidget(Button.builder(Component.literal("+"),
                b -> click(ShipBrainBlockEntity.BUTTON_DISTANCE_UP)).bounds(x + w - 20, y + 24, 20, 20).build());
        stayButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.lost_in_the_sky.ship_brain.stay"),
                b -> click(ShipBrainBlockEntity.BUTTON_STAY)).bounds(x, y + 48, (w - 4) / 2, 20).build());
        offButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.lost_in_the_sky.ship_brain.off"),
                b -> click(ShipBrainBlockEntity.BUTTON_OFF)).bounds(x + (w + 4) / 2, y + 48, (w - 4) / 2, 20).build());
        updateButtons();
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButtons();
    }

    private void updateButtons() {
        boolean onShip = menu.onShip();
        int distance = menu.followDistance();
        followButton.active = onShip;
        stayButton.active = onShip;
        offButton.active = onShip && menu.mode() != ShipBrainBlockEntity.Mode.OFF;
        distanceDown.active = onShip && distance > ShipBrainBlockEntity.MIN_DISTANCE;
        distanceUp.active = onShip && distance < ShipBrainBlockEntity.MAX_DISTANCE;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x0 = leftPos;
        int y0 = topPos;
        graphics.fill(x0, y0, x0 + imageWidth, y0 + imageHeight, BORDER);
        graphics.fill(x0 + 1, y0 + 1, x0 + imageWidth - 1, y0 + imageHeight - 1, BACKGROUND);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 7, TEXT, false);

        Component distance = Component.translatable("gui.lost_in_the_sky.ship_brain.distance", menu.followDistance());
        graphics.drawCenteredString(font, distance, imageWidth / 2, 22 + 24 + 6, TEXT);

        Component linked = Component.translatable("gui.lost_in_the_sky.ship_brain.linked", menu.linkCount());
        graphics.drawCenteredString(font, linked, imageWidth / 2, imageHeight - 26, menu.linkCount() == 0 ? WARN_TEXT : DIM_TEXT);

        Component status;
        int color = DIM_TEXT;
        if (!menu.onShip()) {
            status = Component.translatable("message.lost_in_the_sky.ship_brain.not_on_ship");
            color = WARN_TEXT;
        } else if (menu.locked() && menu.mode() != ShipBrainBlockEntity.Mode.OFF) {
            status = Component.translatable("gui.lost_in_the_sky.ship_brain.locked");
            color = WARN_TEXT;
        } else {
            status = Component.translatable("gui.lost_in_the_sky.ship_brain.mode." + menu.mode().name().toLowerCase(java.util.Locale.ROOT));
        }
        graphics.drawCenteredString(font, status, imageWidth / 2, imageHeight - 14, color);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
