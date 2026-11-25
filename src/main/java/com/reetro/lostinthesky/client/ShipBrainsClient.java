package com.reetro.lostinthesky.client;

import com.reetro.lostinthesky.LostInTheSky;
import com.reetro.lostinthesky.ship.ShipBrains;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client registrations for the ship brain. */
@EventBusSubscriber(modid = LostInTheSky.MODID, value = Dist.CLIENT)
public final class ShipBrainsClient {
    private ShipBrainsClient() {
    }

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ShipBrains.SHIP_BRAIN_MENU.get(), ShipBrainScreen::new);
    }
}
