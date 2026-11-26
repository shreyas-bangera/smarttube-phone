# Third-party notices

StreamTube is built on [SmartTube](https://github.com/yuliskov/SmartTube) (MIT, © yuliskov). It also contains:

- **Kyant backdrop** ([github.com/Kyant0/backdrop](https://github.com/Kyant0/backdrop) v2.0.0), © 2025 Kyant,
  [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). The rounded-rect refraction-with-dispersion and
  specular highlight shaders in `smarttubetv/src/stphone/java/com/liskovsoft/smartyoutubetv2/tv/phone/PhoneTabBarView.java`
  (the Liquid glass tab bar) are ported from it, as MeeTube ported them.
- **NewTube** ([github.com/aleixrodriala/newtube](https://github.com/aleixrodriala/newtube), commit 6f96a1b), MIT,
  © 2020-present yuliskov and NewTube's authors. Ad-free casting to Google Cast TVs: the files in
  `smarttubetv/src/stphone/java/com/liskovsoft/smartyoutubetv2/tv/phone/cast/` (the Cast session, its message codec, the
  phone-side relay server and the DASH manifest rewriter) are adapted from it.
- **MeeTube** ([github.com/yashoncode/meetube](https://github.com/yashoncode/meetube)), MIT, © yuliskov and MeeTube's
  authors. The Liquid glass tab bar's glass, lens and wobble follow MeeTube's (its numbers and approach).
