#
# DC-1 LineageOS GSI product fragment.
#
# This file is the entire DC-1 delta plumbed into the TrebleDroid build.
# The build includes it via:  generate.sh vendor/dc1/common.mk
# It first inherits the LineageOS GSI base product (the same one
# device/phh/treble/lineage.mk would inherit), then layers the DC-1 bits.
#
# Synced into the build tree at vendor/dc1/common.mk by local_manifests/dc1.xml.
#

# --- Base ROM: LineageOS full-phone product (Android 16 / lineage-23.2) ----
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# LineageOS GSI flavor is built with the generic TrebleDroid base
# (device/phh/treble/base.mk) which generate.sh adds automatically.

# --- DC-1 platform properties -----------------------------------------------
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    ro.dc1.product=Daylight_DC1 \
    ro.dc1.amber.setting=screen_brightness_amber_rate \
    ro.dc1.amber.max=1023 \
    ro.dc1.amber.default=1023 \
    ro.dc1.amber.node=

# --- Amber frontlight control app (rootless, system_ext priv-app) -----------
PRODUCT_PACKAGES += \
    AmberControl

# Priv-app permission granting WRITE_SETTINGS etc. to the amber app
PRODUCT_COPY_FILES += \
    vendor/dc1/privapp-permissions-dc1.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-dc1.xml

# --- SELinux: amber app reads/writes the kernel LED node under enforcing ---
# appends to the system_ext sepolicy dirs device/phh/treble already contributes
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/dc1/sepolicy

# --- Monochrome panel: force global grayscale in SurfaceFlinger -------------
# Read in SurfaceFlinger::readPersistentProperties() after boot; applied as
# the global composition color matrix (Rec.709 luma), independent of color
# management. persist. default seeds /data on first boot; a user override of
# the persist prop survives reflashes.
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    persist.sys.sf.color_saturation=0.0

# --- Panel geometry: stock DC-1 ships a 1184x1584 forced display size -------
# (16px bezel ring sits under glass). ro.config.size_override is the WMS
# fallback used when Settings.Global display_size_forced is unset, so it
# applies from the first displayReady() with no on-boot resize flash, and a
# later `wm size` still wins. Physical density (200) is correct as reported
# by vendor, so no density override.
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    ro.config.size_override=1184,1584

# --- Config overlays + package removals (see rro/*/Android.bp) --------------
PRODUCT_PACKAGES += \
    DC1Overlay \
    DC1SettingsProviderOverlay

# --- Mask camera + light-sensor features declared by the stock /vendor ------
PRODUCT_COPY_FILES += \
    vendor/dc1/dc1-excluded-hardware.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/dc1-excluded-hardware.xml

# vim: ft=make
