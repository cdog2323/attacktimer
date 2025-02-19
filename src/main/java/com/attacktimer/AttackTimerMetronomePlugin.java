package com.attacktimer;

import com.google.inject.Provides;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import javax.inject.Inject;
import net.runelite.http.api.item.ItemEquipmentStats;
import net.runelite.http.api.item.ItemStats;

import java.awt.*;

@PluginDescriptor(
        name = "Attack Timer Metronome",
        description = "Shows a visual cue on an overlay every game tick to help timing based activities",
        tags = {"timers", "overlays", "tick", "skilling"}
)
public class AttackTimerMetronomePlugin extends Plugin
{
    public enum AttackState {
        NOT_ATTACKING,
        DELAYED,
        //PENDING,
    }

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ConfigManager configManager;

    @Inject
    private AttackTimerMetronomeTileOverlay overlay;

    @Inject
    private AttackTimerBarOverlay barOverlay;

    @Inject
    private AttackTimerMetronomeConfig config;

    @Inject
    private ItemManager itemManager;

    @Inject
    private Client client;

    public int tickPeriod = 0;

    final int ATTACK_DELAY_NONE = 0;

    private int uiUnshowDebounceTickCount = 0;
    private int uiUnshowDebounceTicksMax = 1;

    public int attackDelayHoldoffTicks = ATTACK_DELAY_NONE;

    public AttackState attackState = AttackState.NOT_ATTACKING;

    public Color CurrentColor = Color.WHITE;

    public int DEFAULT_SIZE_UNIT_PX = 25;

    private final int DEFAULT_FOOD_ATTACK_DELAY_TICKS = 3;
    private final int KARAMBWAN_ATTACK_DELAY_TICKS = 2;
    public Dimension DEFAULT_SIZE = new Dimension(DEFAULT_SIZE_UNIT_PX, DEFAULT_SIZE_UNIT_PX);

    @Provides
    AttackTimerMetronomeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(AttackTimerMetronomeConfig.class);
    }

    private ItemStats getItemStatsFromContainer(ItemContainer container, int slotID)
    {
        final Item item = container.getItem(slotID);
        return item != null ? itemManager.getItemStats(item.getId(), false) : null;
    }
    private ItemStats getWeaponStats()
    {
        return getItemStatsFromContainer(client.getItemContainer(InventoryID.EQUIPMENT),
                EquipmentInventorySlot.WEAPON.getSlotIdx());
    }

    private AttackStyle getAttackStyle()
    {
        final int currentAttackStyleVarbit = client.getVarpValue(VarPlayer.ATTACK_STYLE);
        final int currentEquippedWeaponTypeVarbit = client.getVarbitValue(Varbits.EQUIPPED_WEAPON_TYPE);
        AttackStyle[] attackStyles = WeaponType.getWeaponType(currentEquippedWeaponTypeVarbit).getAttackStyles();

        if (currentAttackStyleVarbit < attackStyles.length) {
            return attackStyles[currentAttackStyleVarbit];
        }

        return AttackStyle.ACCURATE;
    }

    private int getWeaponSpeed()
    {
        ItemStats weaponStats = getWeaponStats();
        if (weaponStats == null) {
            return 4; // Assume barehanded == 4t
        }

        ItemEquipmentStats e = weaponStats.getEquipment();

        int speed = e.getAspeed();
        if (getAttackStyle() == AttackStyle.RANGING &&
            client.getVarpValue(VarPlayer.ATTACK_STYLE) == 1) { // Hack for index 1 => rapid
            speed -= 1; // Assume ranging == rapid.
        }

        return speed; // Deadline for next available attack.
    }

    private boolean isPlayerAttacking()
    {
        return AnimationData.fromId(client.getLocalPlayer().getAnimation()) != null;
    }

    private void performAttack()
    {
        attackState = AttackState.DELAYED;
        attackDelayHoldoffTicks = getWeaponSpeed();
        tickPeriod = attackDelayHoldoffTicks;
        uiUnshowDebounceTickCount = uiUnshowDebounceTicksMax;
    }

    public int getTicksUntilNextAttack()
    {
        return 1 + attackDelayHoldoffTicks;
    }

    public int getWeaponPeriod()
    {
        return tickPeriod;
    }

    public boolean isAttackCooldownPending()
    {
        return (attackState == AttackState.DELAYED) || uiUnshowDebounceTickCount > 0;
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.SPAM)
        {
            return;
        }

        final String message = event.getMessage();

        if (message.startsWith("You eat") ||
                message.startsWith("You drink the wine")) {
            int attackDelay = (message.toLowerCase().contains("karambwan")) ?
                    KARAMBWAN_ATTACK_DELAY_TICKS :
                    DEFAULT_FOOD_ATTACK_DELAY_TICKS;

            if (attackState == AttackState.DELAYED) {
                attackDelayHoldoffTicks += attackDelay;
            }
        }
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged interactingChanged)
    {
        Actor source = interactingChanged.getSource();
        Actor target = interactingChanged.getTarget();

        Player p = client.getLocalPlayer();

        if (source.equals(p) && (target instanceof NPC)) {

            switch (attackState) {
                case NOT_ATTACKING:
                    // If not previously attacking, this action can result in a queued attack or
                    // an instant attack. If its queued, don't trigger the cooldown yet.
                    if (isPlayerAttacking()) {
                       performAttack();
                    }
                    break;

                //case PENDING:
                case DELAYED:
                    // Don't reset tick counter or tick period.
                    break;
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        boolean isAttacking = isPlayerAttacking(); // Heuristic for attacking based on animation.

        switch (attackState) {
            case NOT_ATTACKING:
                if (isAttacking) {
                    performAttack(); // Sets state to DELAYED.
                } else {
                    uiUnshowDebounceTickCount--;
                }
                break;
            case DELAYED:
                if (attackDelayHoldoffTicks <= 0) { // Eligible for a new attack
                    if (isAttacking) {
                        // Found an attack animation. Assume auto attack triggered.
                        performAttack();
                    } else {
                        // No attack animation; assume no attack.
                        attackState = AttackState.NOT_ATTACKING;
                    }
                }
        }

        attackDelayHoldoffTicks--;
    }


    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event.getGroup().equals("attacktimermetronome"))
        {
            attackDelayHoldoffTicks = 0;
        }
    }

    @Override
    protected void startUp() throws Exception
    {
        overlayManager.add(overlay);
        overlay.setPreferredSize(DEFAULT_SIZE);
        overlayManager.add(barOverlay);
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);
        overlayManager.remove(barOverlay);
        attackDelayHoldoffTicks = 0;
    }
}
