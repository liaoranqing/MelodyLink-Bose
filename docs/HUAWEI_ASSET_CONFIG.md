# Huawei asset-backed profiles

MelodyLink now treats Huawei model metadata the same way as Sony metadata:

- `app/src/main/assets/huawei/registry.json` is the only entry point.
- Each file in `app/src/main/assets/huawei/config/` contains the stable model id, exact Bluetooth aliases, image asset, battery layout, and verified ANC capabilities.
- `HuaweiConfigLoader` validates schema, ids, aliases, capability consistency, and image path prefixes before a profile enters `HuaweiDeviceCatalog`.
- The Xposed module adds its APK asset path during initialization, loads both vendor registries, and fails closed for malformed or missing profiles.
- Huawei product images are materialized into the target app's private files directory only after a registered device match. They are not referenced through another APK's resource ids.

The bundled images were copied from `HuaweiPods/app/src/main/res/drawable-nodpi`:

| Asset | Source | Profiles |
| --- | --- | --- |
| `freebuds3.png` | `img_box.png` | FreeBuds 3 and models without a dedicated upstream image |
| `freebuds5.png` | `img_freebuds5_box.png` | FreeBuds 5 |
| `freebuds6i.png` | `img_freebuds6i_box.png` | FreeBuds 6i |
| `freeclip2.png` | `img_freeclip2_box.png` | FreeClip 2 |
| `eyewear2.png` | `img_eyewear2_box.png` | Eyewear and Eyewear 2 |

HuaweiPods currently does not contain dedicated product artwork for FreeBuds Pro 3/4/5, 7i, or FreeClip. Those profiles intentionally use the existing FreeBuds 3 fallback rather than inventing an unverified product image. Replacing those fallbacks only requires adding a bitmap and changing the corresponding `image` field in its JSON profile.
