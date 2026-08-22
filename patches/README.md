# Patches

The DC-1 delta intentionally avoids patching upstream trees: the entire ROM
delta is plumbed through the product fragment (`common.mk`, included by
`generate.sh vendor/dc1/common.mk`) plus the files it references
(`AmberControl/`, `sepolicy/`, `privapp-permissions-dc1.xml`).

That means there is **currently nothing to apply** — the "patchset" CI
validates is:

1. `device/phh/treble` HEAD still supports the fragment flow
   (`generate.sh <fragment>` still emits `$(call inherit-product, …)`), and
2. our `common.mk` still references paths that exist in this repo.

Patch files are named `<project-path-underscores>__<NNNN>-description.patch`
(e.g. `device_phh_treble__0001-dc1-include-vendor-dc1.patch`). The build
runner and CI apply every `*.patch` in this directory after the upstream
patch layers, mapping `<project-path>` back to tree paths.

Current contents: `device_phh_treble__0001-dc1-include-vendor-dc1.patch`
(appends our `vendor/dc1/common.mk` inherit to `lineage_arm64_bvN4.mk`).

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

against fresh shallow clones of the touched upstream projects.
