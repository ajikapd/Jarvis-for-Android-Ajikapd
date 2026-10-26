# DESIGN_TOKENS

Из `app/src/main/res/values/colors.xml` + фактические hex в layouts.

## Colors

```css
--jarvis-bg: #0A0C10;
--jarvis-bg-live: #0F0E14;
--jarvis-panel: #11151B;
--jarvis-panel-2: #181E26;
--jarvis-panel-3: #202832;
--jarvis-panel-alt: #1A1822;
--jarvis-primary: #E0AD55;
--jarvis-primary-alt: #E3A542;
--jarvis-primary-dark: #A77A32;
--jarvis-cyan: #62D9DF;
--jarvis-danger: #E46B58;
--jarvis-danger-alt: #D9634A;
--jarvis-text: #F4F0E7;
--jarvis-text-alt: #F2EEE6;
--jarvis-muted: #9DA5AE;
--jarvis-muted-alt: #9A96A8;
--jarvis-faint: #626B76;
--jarvis-faint-alt: #635D71;
--jarvis-border: #2B333D;
--nav-bg: #0E1116;
--btn-text-on-gold: #17130B;
```

## Typography
- Titles: 22–24sp bold, `#F4F0E7`
- Body: 12–15sp
- Status / chips: monospace, 10–12sp, letter-spacing ~0.08–0.14
- Section labels: 11sp, `#626B76`, letter-spacing 0.06, UPPERCASE

## Components
- **Gold CTA button**: bg `#E3A542`, text dark, height ~50–52dp
- **Danger action**: bordered/red text `#E46B58`
- **Chip**: muted label + gold value on `#1A1822`
- **Card**: `#11151B` / drawable `bg_card`
- **Search**: `bg_search` pill
- **Bottom nav**: 72dp, `#0E1116`, active gold
- **Orb**: `CoreView` ~210–266dp
- **Control circle**: 38–42dp for Live toolbar

## Themes (settings)
CLASSIC (gold+cyan), ARC REACTOR (cyan), STEALTH (neutral), LIGHT (warm) — preference `theme`.
