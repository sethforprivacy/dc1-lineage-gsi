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

# vim: ft=make
