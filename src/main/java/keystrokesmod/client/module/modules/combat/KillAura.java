package keystrokesmod.client.module.modules.combat;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import keystrokesmod.client.event.impl.*;
import keystrokesmod.client.utils.CombatUtils;
import keystrokesmod.client.utils.MillisTimer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.apache.commons.lang3.RandomUtils;
import com.google.common.eventbus.Subscribe;

import keystrokesmod.client.module.Module;
import keystrokesmod.client.module.modules.client.Targets;
import keystrokesmod.client.module.setting.impl.ComboSetting;
import keystrokesmod.client.module.setting.impl.DoubleSliderSetting;
import keystrokesmod.client.module.setting.impl.SliderSetting;
import keystrokesmod.client.module.setting.impl.TickSetting;
import keystrokesmod.client.utils.CoolDown;
import keystrokesmod.client.utils.Utils;
import net.minecraft.entity.player.EntityPlayer;

public class KillAura extends Module {

    private static EntityPlayer target;
    private List<EntityPlayer> pTargets;
    private CoolDown coolDown = new CoolDown(1);
    private boolean locked;
    public static float yaw, pitch, prevYaw, prevPitch, fixedYaw, fixedPitch;
    private double cps;
    private long lastClick;
    private long hold;
    private boolean blocking;
    private double speed;
    private double holdLength;
    private double min;
    private double max;
    private boolean stopClicker = false;
    MillisTimer clickTimer = new MillisTimer();
    public static SliderSetting reach, rps;
    private DoubleSliderSetting aps;
    private final TickSetting fixMovement, legitAttack, visuals, customRPS, weaponOnly;
    public static ComboSetting<BlockMode> blockMode;

    public KillAura() {
        super("KillAura", ModuleCategory.combat);
        this.registerSetting(reach = new SliderSetting("Reach", 3.3, 3, 6, 0.05));
        this.registerSetting(aps = new DoubleSliderSetting("Left CPS", 9, 13, 1, 60, 0.5));
        this.registerSetting(customRPS = new TickSetting("Custom Rotation Speed", false));
        this.registerSetting(rps = new SliderSetting("Rotation Speed", 50, 10, 100, 1));
        this.registerSetting(legitAttack = new TickSetting("Use Legit Clicker", true));
        this.registerSetting(weaponOnly = new TickSetting("Weapon Only", false));
        this.registerSetting(fixMovement = new TickSetting("Movement Fix", true));
        this.registerSetting(visuals = new TickSetting("Visuals", false));
        this.registerSetting(blockMode = new ComboSetting<>("Block Mode", BlockMode.Legit));
    }

    @Subscribe
    public void gameLoopEvent(GameLoopEvent e) {
        try {
            EntityPlayer pTarget = Targets.getTarget();
            if ((pTarget == null) || (mc.currentScreen != null) || !coolDown.hasFinished() ||
                (weaponOnly.isToggled() && !Utils.Player.isPlayerHoldingWeapon())) {
                target = null;
                rotate(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, true);
                return;
            }
            target = pTarget;
            float[] i = Utils.Player.getTargetRotations(target, 0);
            locked = false;
            rotate(i[0], i[1], false);
        } catch (Exception ignored) {}
    }

    @Subscribe
    public void onTick(TickEvent event) {
        if (!Utils.Player.isPlayerInGame()) return;

        if (target != null && Utils.Player.isPlayerHoldingSword()) {
            switch (blockMode.getMode()) {
                case Legit:
                    if (mc.thePlayer.prevSwingProgress < mc.thePlayer.swingProgress && mc.thePlayer.ticksExisted % 15 == 0)
                        KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getKeyCode());
                    break;

                case Vanilla:
                    block();
                    break;

                case Watchdog18:
                    if (mc.thePlayer.ticksExisted % 2 == 0)
                        block();
                    else
                        unblock();
                    break;

                case Watchdog112:
                    if (mc.thePlayer.ticksExisted % 3 == 0)
                        block();
                    else
                        unblock();
                    break;

                case Intave:
                    if (mc.thePlayer.hurtTime > 0 || mc.thePlayer.ticksExisted % 6 == 0)
                        block();
                    else
                        unblock();
                    break;

                case IntaveOld:
                    if (mc.thePlayer.ticksExisted % 10 == 0)
                        block();
                    break;

                case NCP:
                    if (mc.thePlayer.ticksExisted % 4 == 0)
                        block();
                    else
                        unblock();
                    break;

                case NewNCP:
                    if (mc.thePlayer.swingProgress > 0.5F)
                        block();
                    else
                        unblock();
                    break;

                case Fake:
                    // Only pretend to block locally, no packet send
                    mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), 20);
                    break;

                default:
                    unblock();
                    break;
            }
        }

        if (!legitAttack.isToggled()) return;

        if (target != null) {
            if (System.currentTimeMillis() - lastClick > speed * 1000) {
                lastClick = System.currentTimeMillis();
                if (hold < lastClick) hold = lastClick;
                int key = mc.gameSettings.keyBindAttack.getKeyCode();
                KeyBinding.setKeyBindState(key, true);
                KeyBinding.onTick(key);
                this.updateVals();
            } else if (System.currentTimeMillis() - hold > holdLength * 1000) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                this.updateVals();
            }
        } else {
            if (!stopClicker) {
                if (mc.gameSettings.keyBindAttack.pressed)
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
                stopClicker = true;
            }
        }
    }

    @Subscribe
    public void onClickerUpdate(UpdateEvent event) {
        if (legitAttack.isToggled()) return;
        Entity casted = CombatUtils.raycastEntity(reach.getInput(),
                entity -> entity.isEntityAlive() && entity.canBeCollidedWith() &&
                        !entity.isDead && entity == target && CombatUtils.canEntityBeSeen(entity));
        syncClicker();
        if (event.isPre()) {
            if (casted != null && Utils.Player.isPlayerHoldingSword()) {
                block();
            } else unblock();

            if (clickTimer.hasElapsed((long) (1000L / cps))) {
                if (casted != null) {
                    mc.thePlayer.swingItem();
                    mc.playerController.attackEntity(mc.thePlayer, casted);
                    clickTimer.reset();
                }
            }
        }
    }

    private void block() {
        if (blocking) return;
        this.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getCurrentEquippedItem());
        mc.thePlayer.sendQueue.addToSendQueue(
                new C08PacketPlayerBlockPlacement(new BlockPos(-1, -1, -1), 255,
                        mc.thePlayer.getHeldItem(), 0.0f, 0.0f, 0.0f));
        mc.gameSettings.keyBindUseItem.pressed = true;
        blocking = true;
    }

    private void unblock() {
        if (!blocking) return;
        mc.gameSettings.keyBindUseItem.pressed = false;
        mc.getNetHandler().addToSendQueue(
                new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                        BlockPos.ORIGIN, EnumFacing.DOWN));
        blocking = false;
    }

    public void sendUseItem(EntityPlayer playerIn, World worldIn, ItemStack itemStackIn) {
        if (mc.playerController.getCurrentGameType() != WorldSettings.GameType.SPECTATOR) {
            int i = itemStackIn.stackSize;
            ItemStack itemstack = itemStackIn.useItemRightClick(worldIn, playerIn);
            if (itemstack != itemStackIn || itemstack.stackSize != i) {
                playerIn.inventory.mainInventory[playerIn.inventory.currentItem] = itemstack;
                if (itemstack.stackSize == 0) {
                    playerIn.inventory.mainInventory[playerIn.inventory.currentItem] = null;
                }
            }
        }
    }

    private void syncClicker() {
        double min = aps.getInputMin();
        double max = aps.getInputMax();
        if (min > max) min = max;
        cps = (min == max) ? min : RandomUtils.nextDouble(min, max);
    }

    private void updateVals() {
        stopClicker = false;
        min = aps.getInputMin();
        max = aps.getInputMax();
        if (min >= max) max = min + 1;
        speed = 1.0 / ThreadLocalRandom.current().nextDouble(min - 0.2, max);
        holdLength = speed / ThreadLocalRandom.current().nextDouble(min, max);
    }
    public void rotate(float targetYaw, float targetPitch, boolean reset) {
    if (reset) {
        yaw = mc.thePlayer.rotationYaw;
        pitch = mc.thePlayer.rotationPitch;
        return;
    }

    // Smooth interpolation step for gradual rotation
    float smoothSpeed = (float) (rps.getInput() / 100.0f); // uses Rotation Speed slider
    float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - yaw);
    float pitchDiff = targetPitch - pitch;

    // Apply smooth interpolation and micro jitter
    float smoothYaw = yaw + yawDiff * smoothSpeed;
    float smoothPitch = pitch + pitchDiff * smoothSpeed;

    // Add minor humanized noise
    float jitterYaw = (float) (ThreadLocalRandom.current().nextGaussian() * 0.2);
    float jitterPitch = (float) (ThreadLocalRandom.current().nextGaussian() * 0.15);

    yaw = smoothYaw + jitterYaw;
    pitch = smoothPitch + jitterPitch;

    // Clamp pitch to realistic limits
    if (pitch > 90) pitch = 90;
    if (pitch < -90) pitch = -90;
}
    @Subscribe
public void onRotationUpdate(UpdateEvent e) {
    if (!Utils.Player.isPlayerInGame() || locked) return;

    // Easing-based smooth turn
    float ease = (float) (Math.pow(rps.getInput() / 100.0, 1.2));
    float[] currentRots = {yaw, pitch};
    float[] prevRots = {prevYaw, prevPitch};
    float[] cappedRots = {
            maxAngleChange(prevRots[0], currentRots[0], (float) (ease * 5)),
            maxAngleChange(prevRots[1], currentRots[1], (float) (ease * 5))
    };
    float[] gcd = getGCDRotations(customRPS.isToggled() ? cappedRots : currentRots, prevRots);

    e.setYaw(gcd[0]);
    e.setPitch(gcd[1]);
    mc.thePlayer.renderYawOffset = gcd[0];
    mc.thePlayer.rotationYawHead = gcd[0];
    fixedYaw = gcd[0];
    fixedPitch = gcd[1];
    prevYaw = e.getYaw();
    prevPitch = e.getPitch();
}

    public void onDisable() {
        target = null;
        unblock();
    }

    public enum BlockMode {
        None,
        Legit,
        Vanilla,
        Fake,
        Watchdog18,
        Watchdog112,
        Intave,
        IntaveOld,
        NCP,
        NewNCP;
    }
}
