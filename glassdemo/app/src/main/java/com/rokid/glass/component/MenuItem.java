package com.rokid.glass.component;

import androidx.annotation.Keep;

@Keep
public class MenuItem {

    private static final int PAGE_MAX_SIZE = 3;

    private String menuName;                 // 菜单名称
    /**
     * 菜单类型：
     */
    private String menuType;
    private int unCheckIconId;              // 未选中的本地资源图片
    private int checkedIconId;              // 选中的本地资源图片

    private int menuNumId;                  // 菜单序号图片
    private boolean hasNewMessage;          // 是否有新消息，用于标识红点
    private boolean checked;                // 是否是选中项

    private int unCheckItemBgId;              // 未选中的菜单项背景图片ID
    private int checkedItemBgId;              // 选中的菜单项背景图片ID

    /**
     * MainActivity 中固定
     * @param menuName
     * @param menuType
     * @param iconDefaultRecourseId
     * @param iconFocusRecourseId
     * @param checked
     * @param hasNewMessage
     */
    public MenuItem(String menuName, String menuType, int iconDefaultRecourseId, int iconFocusRecourseId,int itemBgDefaultResId,int itemBgFocusResId, boolean checked, boolean hasNewMessage) {
        this.menuName = menuName;
        this.menuType = menuType;
        this.unCheckIconId = iconDefaultRecourseId;
        this.checkedIconId = iconFocusRecourseId;
        this.checkedItemBgId = itemBgFocusResId;
        this.unCheckItemBgId = itemBgDefaultResId;
        this.checked = checked;
        this.hasNewMessage = hasNewMessage;
    }

    public MenuItem(String menuName, String menuType, int iconDefaultRecourseId, int iconFocusRecourseId, int itemBgDefaultResId,int itemBgFocusResId,boolean checked) {
        this.menuName = menuName;
        this.menuType = menuType;
        this.unCheckIconId = iconDefaultRecourseId;
        this.checkedIconId = iconFocusRecourseId;
        this.checkedItemBgId = itemBgFocusResId;
        this.unCheckItemBgId = itemBgDefaultResId;
        this.checked = checked;
    }

    public String getMenuName() {
        return menuName;
    }

    public int getUnCheckIconId() {
        return unCheckIconId;
    }

    public int getCheckedIconId() {
        return checkedIconId;
    }

    public boolean isChecked() {
        return checked;
    }

    public boolean isHasNewMessage() {
        return hasNewMessage;
    }

    public String getMenuType() {
        return menuType;
    }

    public void setHasNewMessage(boolean hasNewMessage) {
        this.hasNewMessage = hasNewMessage;
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    public int getMenuNumId() {
        return menuNumId;
    }

    public int getUnCheckItemBgId() {
        return unCheckItemBgId;
    }

    public void setUnCheckItemBgId(int unCheckItemBgId) {
        this.unCheckItemBgId = unCheckItemBgId;
    }

    public int getCheckedItemBgId() {
        return checkedItemBgId;
    }

    public void setCheckedItemBgId(int checkedItemBgId) {
        this.checkedItemBgId = checkedItemBgId;
    }
}
