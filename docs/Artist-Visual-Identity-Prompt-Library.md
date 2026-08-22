# Ticketing Website — Pixel Art Artist Visual Identity Prompt Library

> ## ⚠️ 2026-08-22: This document is superseded
>
> **Two of its foundations no longer hold:**
>
> 1. **The four artist entries (ZUTOMAYO / Sunset Rollercoaster / HYUKOH / Soft Lipa) are void.**
>    Band names are now **fictional**, so there is no real artist whose Visual DNA can be looked up.
> 2. **`GLOBAL ART DIRECTION` is void.** It mandates that all artwork belong to one unified
>    "32-bit Pixel Art Music Universe". The current direction is the opposite — deliberately
>    **diverse** styles (minimal / photographic / animated / abstract / print), following how
>    real indie ticketing sites look. Pixel art is demoted from *the* style to **one entry in
>    a style library**.
>
> ✅ **Still valid**: the copyright prohibitions at the top (no existing album covers, posters,
> MV scenes, logos, real likenesses) and the rule that no typography is rendered inside the image.
> Both are carried over into poster-forge.
>
> Superseded by poster-forge 專案(`C:/Users/USER/Documents/poster-forge`,決策紀錄見其 `CLAUDE.md` §2). The pixel-art direction survives as style `pixel-32bit` in
> `poster-forge/src/styles/library.ts`.

## Purpose

This document defines the visual identity and image-generation prompts for event artwork used by the ticketing website.

The goal is to create artwork that reflects the **musical atmosphere, cultural context, and general visual identity** associated with each artist while maintaining a unified original Pixel Art direction across the website.

The generated artwork must NOT reproduce:

* Existing album covers
* Existing concert posters
* Existing music video scenes
* Official logos
* Copyrighted characters or mascots
* Real artist likenesses
* Existing promotional photography

---

# GLOBAL ART DIRECTION

All event artwork belongs to the same visual universe.

## Core Style

**Detailed 32-bit Pixel Art Music Universe**

The images should feel like high-quality artwork from a sophisticated late-1990s / early-2000s Japanese PC or console game rather than simplistic modern pixel icons.

## Rendering Style

Use:

* Detailed 32-bit pixel art
* Crisp pixel edges
* Carefully designed pixel clusters
* Subtle dithering
* Limited but expressive color palettes
* Cinematic lighting
* Strong atmospheric depth
* Rich environmental storytelling
* Retro Japanese PC / console game aesthetic
* Sophisticated independent music artwork
* Intentional composition

Avoid:

* Vector-like graphics
* Smooth digital painting
* Photorealistic rendering
* 3D rendering
* Excessive anti-aliasing
* Chibi characters
* Cute mobile-game aesthetic
* Generic AI anime appearance

## Composition

Default output:

**16:9 horizontal concert banner**

The artwork should work as:

* Homepage hero artwork
* Event banner
* Event detail header
* Promotional website artwork

Do NOT generate artist names, event names, dates, ticket prices or other typography inside the image.

The website UI will render all textual information separately.

Where possible, preserve intentional negative space for website typography.

---

# 01 — ZUTOMAYO

## Visual DNA

### Genre

Japanese alternative rock / experimental pop / electronic rock

### Mood

Mysterious / chaotic / energetic / surreal / nocturnal

### Visual Language

* Japanese urban environment
* Surreal city
* Dense environmental details
* Nighttime
* Electrical wires
* Mechanical objects
* Aquatic imagery
* Neon lighting
* Rain
* Rooftops
* Dramatic perspective
* Controlled visual chaos
* Female alternative-rock archetype

### Color Direction

Electric cyan / deep navy / turquoise / warm orange highlights

---

## Master Prompt

Highly detailed 32-bit Japanese pixel art concert poster,

a mysterious original fictional female alternative-rock vocalist standing on the rooftop of a chaotic Japanese city at midnight,

long dark hair blowing in the wind,
oversized contemporary streetwear,
an electric guitar resting beside her,

dense Tokyo-like buildings stretching into the distance,
power lines crossing between buildings,
glowing vending machines,
rooftop antennas,
strange mechanical devices,
narrow illuminated streets far below,

surreal tropical fish and mysterious aquatic creatures floating through the nighttime city as if the entire city were slowly transforming into an enormous aquarium,

subtle aquarium reflections appearing across windows and wet surfaces,

deep navy night sky,
electric cyan, turquoise and warm orange lighting,
wet surfaces reflecting city lights,

energetic asymmetric composition,
dramatic perspective,
mysterious nocturnal atmosphere,
controlled visual chaos,
dense environmental storytelling,

Japanese alternative rock culture,
experimental pop atmosphere,
surreal urban fantasy,
sophisticated Japanese music editorial aesthetic,

completely original fictional character,
original environment,
no resemblance to any real musician,
no existing anime or music-video characters,
no existing album artwork,
no logos,

detailed 32-bit pixel art,
high-quality pixel clusters,
crisp pixel edges,
subtle dithering,
limited expressive color palette,
retro Japanese PC and console game aesthetic,

16:9 horizontal cinematic concert banner,

preserve intentional dark negative space for website typography.

---

## Negative Prompt

ZUTOMAYO logo,
existing ZUTOMAYO characters,
Nira character,
existing music video characters,
existing album cover,
existing concert poster,
existing music video scene,
artist portrait,
celebrity likeness,
real musician likeness,
copyrighted mascot,
official branding,
text,
letters,
event title,
watermark,
signature,
photorealistic,
3D render,
smooth digital painting,
generic anime,
chibi,
mobile game art,
malformed hands,
duplicated characters.

---

# 02 — SUNSET ROLLERCOASTER

## Visual DNA

### Genre

Dream pop / psychedelic soul / indie rock / city pop

### Mood

Warm / nostalgic / romantic / dreamy / relaxed

### Visual Language

* 1970s Asian coastal atmosphere
* Sunset
* Tropical scenery
* Vintage motel
* Ocean
* Convertible cars
* Palm trees
* Analog synthesizers
* Electric guitars
* Psychedelic imagery
* Summer evening
* Retro Asian city-pop atmosphere

### Color Direction

Burnt orange / dusty pink / faded yellow / ocean blue / cream

---

## Master Prompt

Highly detailed 32-bit pixel art indie concert poster,

a completely fictional five-piece Asian psychedelic indie band relaxing outside a vintage seaside motel at sunset,

the musicians casually sitting and standing around the motel parking area,

loose vintage shirts,
retro sunglasses,
relaxed summer clothing,

electric guitars,
bass guitar,
a vintage analog synthesizer and small amplifier nearby,

an old convertible parked beside the motel,

1970s Taiwanese coastal atmosphere,

tall palm trees moving gently in the warm evening wind,
the ocean visible behind the motel,
orange sunlight reflecting across the water,

a huge slightly surreal sunset hanging above the horizon,

dreamy psychedelic clouds,
subtle geometric distortions appearing in the sky and ocean,

warm humid summer evening,
romantic nostalgic atmosphere,
quiet sense of freedom,

psychedelic soul,
dream pop,
Asian city-pop atmosphere,
1970s record-cover mood,
independent music culture,

completely original fictional musicians,
no resemblance to real band members,
original environment,
no existing album artwork,
no existing promotional photography,
no logos,

detailed 32-bit pixel art,
retro console aesthetic,
carefully designed pixel clusters,
subtle dithering creating an analog film-grain feeling,
slightly faded vintage palette,
crisp pixel edges,

16:9 horizontal cinematic concert banner,

preserve large areas of sunset sky as negative space for website typography.

---

## Negative Prompt

Sunset Rollercoaster logo,
real Sunset Rollercoaster band members,
celebrity likeness,
real musician likeness,
existing album cover,
existing concert poster,
existing promotional photography,
existing music video scene,
official branding,
text,
letters,
event title,
watermark,
signature,
photorealistic,
3D rendering,
smooth illustration,
modern corporate stock photography,
generic tropical travel advertisement,
chibi,
mobile game aesthetic.

---

# 03 — HYUKOH

## Visual DNA

### Genre

Korean indie rock / alternative rock

### Mood

Lonely / introspective / understated / youthful / melancholic

### Visual Language

* Seoul urban environment
* Brutalist architecture
* Concrete
* Empty public spaces
* Apartment buildings
* Overcast skies
* Oversized clothing
* Documentary photography translated into Pixel Art
* 1990s urban nostalgia
* Youthful loneliness
* Minimal composition

### Color Direction

Muted grey / faded green / beige / washed blue / black

---

## Master Prompt

Highly detailed 32-bit pixel art Korean indie rock concert poster,

four completely fictional young Korean indie musicians standing far apart from each other inside an enormous empty concrete plaza,

massive brutalist apartment buildings surrounding the plaza,

overcast Seoul afternoon,
cloudy pale sky,
distant apartment towers slowly disappearing into atmospheric haze,

the musicians wearing understated oversized vintage clothing,

electric guitar,
bass guitar,
small amplifier cases nearby,

an empty basketball court,
an old bicycle leaning against a concrete wall,
a tiny convenience store glowing quietly in the distance,

large areas of empty concrete emphasizing isolation and scale,

awkward candid body language,
quiet youthful loneliness,
introspective atmosphere,
subtle melancholy,

minimal Korean indie aesthetic,
1990s urban nostalgia,
alternative rock culture,
documentary photography composition translated into pixel art,

muted grey,
faded green,
washed blue,
beige and subtle black palette,

completely original fictional musicians,
no resemblance to any real musician,
original environment,
no existing album artwork,
no existing music video scene,
no logos,

cinematic detailed pixel art,
retro Korean and Japanese PC game aesthetic,
subtle dithering,
carefully controlled pixel clusters,
minimal but atmospheric composition,
crisp pixel edges,

16:9 horizontal cinematic concert banner,

use the massive architecture and overcast sky to create strong negative space for website typography.

---

## Negative Prompt

HYUKOH logo,
Oh Hyuk likeness,
real HYUKOH band members,
celebrity likeness,
real musician likeness,
existing HYUKOH album cover,
existing concert poster,
existing music video scene,
existing concert photography,
official branding,
text,
letters,
event title,
watermark,
signature,
photorealistic,
glossy commercial photography,
3D render,
smooth digital illustration,
bright colorful pop aesthetic,
chibi,
mobile game art.

---

# 04 — SOFT LIPA / 蛋堡

## Visual DNA

### Genre

Taiwanese hip-hop / jazz rap / chill hip-hop

### Mood

Relaxed / urban / nostalgic / intellectual / late-night / understated

### Visual Language

* Taipei neighborhoods
* Old apartment buildings
* Scooters
* Record stores
* Convenience stores
* Jazz bars
* Vinyl culture
* Humid summer nights
* Early-2000s Taipei
* Street photography
* Underground hip-hop culture

### Color Direction

Warm tungsten / dark green / brown / faded red / cream / midnight blue

---

## Master Prompt

Highly detailed 32-bit pixel art Taiwanese jazz hip-hop concert poster,

a completely fictional Taiwanese underground rapper sitting casually outside a tiny independent record store in Taipei at 1 AM,

wearing a simple bucket hat,
oversized plain T-shirt,
loose pants,
casual sneakers,

relaxed understated body language,

a crate filled with vinyl records sitting beside him,

an old scooter parked near the sidewalk,

narrow Taipei street surrounded by aging apartment buildings,

air conditioners mounted outside windows,
metal balconies,
old Taiwanese shop signs,
electrical wires crossing above the street,

a convenience store glowing quietly across the road,

a tiny jazz bar located upstairs with warm light coming through the windows,

vinyl records visible through the record-store window,

humid summer night,
warm tungsten street lights,
deep midnight shadows,
slightly wet pavement,

quiet neighborhood atmosphere,
laid-back late-night feeling,
urban solitude,

Taiwan underground hip-hop culture,
jazz rap,
vinyl culture,
early-2000s Taipei nostalgia,
relaxed sophisticated independent music atmosphere,

completely original fictional character,
no resemblance to any real musician,
original Taipei environment,
no existing album artwork,
no existing promotional photography,
no logos,

detailed 32-bit pixel art,
retro Japanese and Taiwanese arcade / PC game aesthetic,
carefully designed pixel clusters,
subtle dithering,
warm analog-like palette,
crisp pixel edges,

16:9 horizontal cinematic concert banner,

preserve darker architectural areas as negative space for website typography.

---

## Negative Prompt

Soft Lipa likeness,
蛋堡 likeness,
real musician,
celebrity likeness,
official Soft Lipa artwork,
existing album cover,
existing concert poster,
existing promotional photograph,
existing music video scene,
official logo,
official branding,
luxury cars,
excessive jewelry,
commercial gangster rap stereotype,
text,
letters,
event title,
watermark,
signature,
photorealistic,
3D rendering,
smooth digital painting,
generic anime,
chibi,
mobile game aesthetic.

---

# GENERATION REQUIREMENTS

Generate new artwork for all four artist event categories:

1. ZUTOMAYO
2. Sunset Rollercoaster
3. HYUKOH
4. Soft Lipa / 蛋堡

Use the corresponding Master Prompt and Negative Prompt defined above.

## Consistency Requirements

All four artworks must clearly belong to the same:

**32-bit Pixel Art Music Universe**

Maintain consistent:

* Pixel density
* Rendering quality
* Level of detail
* Dithering style
* Overall sophistication
* Cinematic presentation

However, do NOT force identical:

* Color palettes
* Environments
* Character designs
* Lighting
* Composition

Each artist must remain visually distinguishable through its own Visual DNA.

## Important

The artwork should evoke the **music culture and atmosphere** associated with each artist category without reproducing identifiable copyrighted artwork.

Do NOT use:

* Existing artist portraits
* Existing band-member likenesses
* Existing album covers
* Existing MV characters
* Existing concert posters
* Official logos

Do NOT render the artist name inside the artwork.

The ticketing website will display the real artist/event information separately through the UI.

## Output

Regenerate all four existing event artworks and replace the previous versions while preserving the application's existing image dimensions, file paths, filenames and references unless a change is technically required.
