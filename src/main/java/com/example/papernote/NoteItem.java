package com.example.papernote;

import com.example.papernote.client.ClientHooks;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class NoteItem extends Item {

    public NoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.openNote(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        String text = NoteData.text(stack);
        if (!text.isEmpty()) {
            String first = text.lines().findFirst().orElse("");
            if (first.length() > 40) first = first.substring(0, 40) + "…";
            tooltip.add(Component.literal(first).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        if (!NoteData.photo(stack).isEmpty()) {
            tooltip.add(Component.translatable("tooltip.papernote.has_photo").withStyle(ChatFormatting.DARK_AQUA));
        }
        tooltip.add(Component.translatable("tooltip.papernote.open").withStyle(ChatFormatting.DARK_GRAY));
    }
}
