# Patches

The DC-1 delta keeps patching of upstream trees to a minimum: nearly all of
it is plumbed through the product fragment (`common.mk`, included by
`generate.sh vendor/dc1/common.mk`) plus the files it references
(`AmberControl/`, `sepolicy/`, `privapp-permissions-dc1.xml`,
`dc1-excluded-hardware.xml`, `rro/`).

Alongside applying every `*.patch` here, CI validates that:

1. `device/phh/treble` HEAD still supports the fragment flow
   (`generate.sh <fragment>` still emits `$(call inherit-product, …)`), and
2. our `common.mk` still references paths that exist in this repo.

Patch files are named `<project-path-underscores>__<NNNN>-description.patch`
(e.g. `device_phh_treble__0001-dc1-include-vendor-dc1.patch`). The build
runner and CI apply every `*.patch` in this directory after the upstream
patch layers, mapping `<project-path>` back to tree paths.

Current contents:

| Patch | What it does |
|---|---|
| `device_phh_treble__0001-dc1-include-vendor-dc1.patch` | appends our `vendor/dc1/common.mk` inherit to `lineage_arm64_bvN4.mk` |
| `vendor_lineage__0001-drop-bootanimation-dark-symlink.patch` | drops the dark boot animation symlink |
| `frameworks_base__0001-dc1-amber-slider-in-qs.patch` | adds the amber warmth slider under the brightness slider in the Quick-Settings panel (SystemUI); the logic is in a new file, the upstream panel file gets a nine-line change |
| `frameworks_base__0002-dc1-amber-rate-as-backlight-alpha.patch` | `LightsService` sends `screen_brightness_amber_rate` to the vendor lights HAL as the alpha byte of the backlight color, when `ro.dc1.amber.driver=framework`. Generated with one line of context so it applies both to pristine LineageOS and on top of the TrebleDroid patch layer |

## Adding a patch

```bash
# in your synced tree, inside the project you changed:
git diff > /path/to/repo/patches/0001-description.patch   # or git format-patch -1
```

## How CI validates this directory

`tools/validate-fork.sh` runs, for every `patches/*.patch`:

```bash
git -C <clone> apply --check --verbose patches/…
```

against fresh shallow clones of the touched upstream projects. For
`device/phh/treble` the clone first gets MisterZtr's lineage-GSI patch layer
(`patches/personal/device_phh_treble/*`) applied best-effort, because that
layer is what creates the `lineage_arm64_bvN4.mk` product our patch appends
to — a pristine TrebleDroid checkout does not contain it.
