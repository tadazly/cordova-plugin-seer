declare namespace CordovaSeer {
    /**
     * 检查当前图标是否为包体主图标
     * iOS: BuildSettings -> Primary App Icon Set Name
     * Android: activities-alias -> enabled: true
     */
    function isPrimaryAppIcon(): Promise<boolean>;

    /**
     * 设置icon
     * @param iconName 图标集名称 Default | Anniversary
     */
    function setAppIcon(iconName: string): Promise<boolean>;

    /**
     * 获取当前icon集名称
     */
    function getCurrentAppIconName(): Promise<string>;
}