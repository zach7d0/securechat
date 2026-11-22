/**
 * Bundled emoji catalogue for the composer picker (no network fetch, no third-party data).
 * Each entry is "<emoji> <search keywords…>"; rendering uses the platform's native emoji font.
 */

export interface EmojiCategory {
  id: string
  label: string
  icon: string
  emojis: { char: string; keywords: string }[]
}

const RAW: [id: string, label: string, icon: string, entries: string][] = [
  ['smileys', 'Smileys & emotion', '😀', `
😀 grinning smile happy|😃 smiley happy joy|😄 smile happy laugh|😁 grin beaming|😆 laughing satisfied|😅 sweat smile relief|🤣 rofl rolling laugh|😂 joy tears laugh lol
🙂 slightly smiling|🙃 upside down silly|😉 wink|😊 blush smile happy|😇 innocent halo angel|🥰 love hearts adore|😍 heart eyes love|🤩 star struck wow|😘 kiss blow love
😗 kissing|☺️ relaxed smile|😚 kissing closed eyes|😙 kissing smiling|🥲 smile tear grateful|😋 yum delicious tongue|😛 tongue out|😜 wink tongue crazy|🤪 zany crazy goofy
😝 squint tongue|🤑 money mouth rich|🤗 hug hugging|🤭 hand over mouth giggle oops|🤫 shush quiet secret|🤔 thinking hmm|🤐 zipper mouth secret|🤨 raised eyebrow skeptical|😐 neutral meh
😑 expressionless|😶 no mouth speechless|😏 smirk|😒 unamused annoyed|🙄 eye roll|😬 grimace awkward|😮‍💨 exhale sigh relief|🤥 lying pinocchio|😌 relieved calm
😔 pensive sad|😪 sleepy|🤤 drooling|😴 sleeping zzz tired|😷 mask sick|🤒 thermometer sick fever|🤕 bandage hurt|🤢 nauseated sick|🤮 vomit sick|🤧 sneeze cold
🥵 hot heat|🥶 cold freezing|🥴 woozy drunk dizzy|😵 dizzy|🤯 mind blown exploding|🤠 cowboy|🥳 party celebrate birthday|🥸 disguise|😎 cool sunglasses|🤓 nerd geek
🧐 monocle curious|😕 confused|😟 worried|🙁 frown sad|☹️ frowning sad|😮 open mouth surprised wow|😯 hushed|😲 astonished shocked|😳 flushed embarrassed|🥺 pleading puppy eyes please
😦 frowning open|😧 anguished|😨 fearful scared|😰 anxious sweat|😥 sad relieved|😢 cry sad tear|😭 sob crying loud|😱 scream fear|😖 confounded|😣 persevere
😞 disappointed|😓 downcast sweat|😩 weary tired|😫 tired exhausted|🥱 yawn bored|😤 triumph huff|😡 pout angry rage|😠 angry mad|🤬 cursing swear|😈 devil smiling
👿 imp angry devil|💀 skull dead lol|☠️ skull crossbones|💩 poop|🤡 clown|👻 ghost boo|👽 alien|🤖 robot|😺 cat smile|😹 cat joy|😻 cat heart eyes
🙈 see no evil monkey|🙉 hear no evil|🙊 speak no evil|💋 kiss lips|💌 love letter|💘 heart arrow cupid|💝 heart ribbon gift|💖 sparkling heart|💗 growing heart|💓 beating heart
💞 revolving hearts|💕 two hearts|💟 heart decoration|❣️ heart exclamation|💔 broken heart|❤️‍🔥 heart fire|❤️ red heart love|🧡 orange heart|💛 yellow heart|💚 green heart
💙 blue heart|💜 purple heart|🤎 brown heart|🖤 black heart|🤍 white heart|💯 hundred perfect score|💢 anger|💥 boom collision|💫 dizzy star|💦 sweat drops|💨 dash fast
💬 speech bubble chat|💭 thought bubble|💤 zzz sleep`],
  ['people', 'People & body', '👋', `
👋 wave hello hi bye|🤚 raised back hand|🖐️ hand fingers splayed|✋ raised hand stop high five|🖖 vulcan|👌 ok perfect|🤌 pinched fingers italian|🤏 pinch small|✌️ victory peace
🤞 crossed fingers luck|🤟 love you gesture|🤘 rock horns|🤙 call me shaka|👈 point left|👉 point right|👆 point up|🖕 middle finger|👇 point down|☝️ index up
👍 thumbs up like yes good|👎 thumbs down dislike no|✊ raised fist|👊 punch fist bump|🤛 left fist|🤜 right fist|👏 clap applause|🙌 raised hands celebrate hooray|👐 open hands|🤲 palms up
🤝 handshake deal agree|🙏 pray please thanks|✍️ writing|💅 nail polish|🤳 selfie|💪 muscle strong flex|🧠 brain smart|👀 eyes look|👁️ eye|👅 tongue|👄 mouth
👶 baby|🧒 child|👦 boy|👧 girl|🧑 person|👱 blond|👨 man|🧔 beard|👩 woman|🧓 older person|👴 old man|👵 old woman
🙍 frowning person|🙎 pouting person|🙅 no gesture|🙆 ok gesture|💁 tipping hand|🙋 raising hand|🧏 deaf|🙇 bow|🤦 facepalm|🤷 shrug idk
👮 police|🕵️ detective|💂 guard|👷 construction worker|🤴 prince|👸 princess|👳 turban|🤵 tuxedo groom|👰 bride wedding|🤰 pregnant|🤱 breastfeeding
👼 baby angel|🎅 santa christmas|🤶 mrs claus|🦸 superhero|🦹 supervillain|🧙 mage wizard|🧚 fairy|🧛 vampire|🧜 merperson|🧝 elf|🧞 genie|🧟 zombie
💆 massage|💇 haircut|🚶 walking|🧍 standing|🧎 kneeling|🏃 running|💃 dancing woman|🕺 dancing man|👯 bunny ears party|🧖 sauna|🧗 climbing
👫 couple holding hands|👭 women holding hands|👬 men holding hands|💏 kiss couple|💑 couple heart|👪 family|🗣️ speaking head|👤 bust silhouette|👥 busts|🫂 people hugging`],
  ['nature', 'Animals & nature', '🐶', `
🐶 dog puppy|🐱 cat kitten|🐭 mouse|🐹 hamster|🐰 rabbit bunny|🦊 fox|🐻 bear|🐼 panda|🐨 koala|🐯 tiger|🦁 lion|🐮 cow|🐷 pig|🐸 frog
🐵 monkey|🐔 chicken|🐧 penguin|🐦 bird|🐤 chick|🦆 duck|🦅 eagle|🦉 owl|🦇 bat|🐺 wolf|🐗 boar|🐴 horse|🦄 unicorn|🐝 bee honeybee
🐛 bug caterpillar|🦋 butterfly|🐌 snail|🐞 ladybug|🐜 ant|🕷️ spider|🦂 scorpion|🐢 turtle|🐍 snake|🦎 lizard|🦖 t-rex dinosaur|🦕 sauropod dinosaur|🐙 octopus
🦑 squid|🦐 shrimp|🦀 crab|🐡 blowfish|🐠 tropical fish|🐟 fish|🐬 dolphin|🐳 whale|🦈 shark|🐊 crocodile|🐅 tiger|🐆 leopard|🦓 zebra|🦍 gorilla
🐘 elephant|🦛 hippo|🦏 rhino|🐪 camel|🦒 giraffe|🦘 kangaroo|🐃 buffalo|🐄 cow|🐎 racehorse|🐖 pig|🐑 sheep|🐐 goat|🦌 deer|🐕 dog|🐈 cat|🐓 rooster
🦃 turkey|🦚 peacock|🦜 parrot|🦢 swan|🦩 flamingo|🕊️ dove peace|🐇 rabbit|🦝 raccoon|🦨 skunk|🦡 badger|🦦 otter|🦥 sloth|🐁 mouse|🐿️ chipmunk|🦔 hedgehog
🐾 paw prints|💐 bouquet flowers|🌸 cherry blossom|💮 white flower|🌹 rose|🥀 wilted flower|🌺 hibiscus|🌻 sunflower|🌼 blossom|🌷 tulip|🌱 seedling|🪴 potted plant
🌲 evergreen tree|🌳 tree|🌴 palm tree|🌵 cactus|🌾 rice sheaf|🌿 herb|☘️ shamrock|🍀 four leaf clover luck|🍁 maple leaf|🍂 fallen leaf autumn|🍃 leaves wind|🍄 mushroom
🌍 earth globe world|🌙 crescent moon night|🌚 new moon face|🌝 full moon face|🌞 sun face|⭐ star|🌟 glowing star|✨ sparkles|⚡ lightning zap|🔥 fire hot lit
🌈 rainbow|☀️ sun sunny|⛅ partly cloudy|☁️ cloud|🌧️ rain|⛈️ thunderstorm|❄️ snowflake cold|☃️ snowman|⛄ snowman|🌊 wave ocean sea|💧 droplet water|☔ umbrella rain`],
  ['food', 'Food & drink', '🍔', `
🍏 green apple|🍎 apple|🍐 pear|🍊 orange tangerine|🍋 lemon|🍌 banana|🍉 watermelon|🍇 grapes|🍓 strawberry|🫐 blueberries|🍈 melon|🍒 cherries|🍑 peach|🥭 mango
🍍 pineapple|🥥 coconut|🥝 kiwi|🍅 tomato|🍆 eggplant|🥑 avocado|🥦 broccoli|🥬 leafy green|🥒 cucumber|🌶️ hot pepper chili spicy|🌽 corn|🥕 carrot|🧄 garlic|🧅 onion
🥔 potato|🍠 sweet potato|🥐 croissant|🥯 bagel|🍞 bread|🥖 baguette|🧀 cheese|🥚 egg|🍳 cooking fried egg|🥞 pancakes|🧇 waffle|🥓 bacon|🥩 steak meat|🍗 chicken leg
🍖 meat bone|🌭 hot dog|🍔 burger hamburger|🍟 fries|🍕 pizza|🥪 sandwich|🌮 taco|🌯 burrito|🥗 salad|🥘 paella|🍝 spaghetti pasta|🍜 ramen noodles|🍲 stew pot|🍛 curry rice
🍣 sushi|🍱 bento|🥟 dumpling|🍤 fried shrimp tempura|🍙 rice ball onigiri|🍚 rice|🍘 rice cracker|🍥 fish cake|🥮 moon cake|🍢 oden|🍡 dango|🍧 shaved ice|🍨 ice cream|🍦 soft serve
🥧 pie|🧁 cupcake|🍰 cake shortcake|🎂 birthday cake|🍮 custard pudding|🍭 lollipop|🍬 candy|🍫 chocolate|🍿 popcorn|🍩 doughnut donut|🍪 cookie|🌰 chestnut|🥜 peanuts|🍯 honey
🥛 milk|🍼 baby bottle|☕ coffee tea hot|🍵 tea|🧃 juice box|🥤 soda cup|🧋 bubble tea boba|🍶 sake|🍺 beer|🍻 cheers beers|🥂 champagne toast cheers|🍷 wine|🥃 whisky|🍸 cocktail martini
🍹 tropical drink|🍾 champagne bottle celebrate|🧊 ice|🥄 spoon|🍴 fork knife|🍽️ plate dinner|🥢 chopsticks|🧂 salt`],
  ['activity', 'Activities', '⚽', `
⚽ soccer football|🏀 basketball|🏈 american football|⚾ baseball|🥎 softball|🎾 tennis|🏐 volleyball|🏉 rugby|🥏 frisbee|🎱 pool 8 ball|🏓 ping pong|🏸 badminton|🏒 hockey
🥍 lacrosse|🏏 cricket|⛳ golf|🏹 archery bow|🎣 fishing|🤿 diving|🥊 boxing|🥋 martial arts|🎽 running shirt|🛹 skateboard|⛸️ ice skate|🎿 ski|🏂 snowboard|🏋️ weight lifting gym
🤸 cartwheel|⛹️ bouncing ball|🤺 fencing|🤾 handball|🏌️ golfing|🏇 horse racing|🧘 yoga meditate|🏄 surfing|🏊 swimming|🚴 biking cycling|🏆 trophy win champion|🥇 gold medal first|🥈 silver medal|🥉 bronze medal
🏅 medal|🎖️ military medal|🎗️ ribbon|🎫 ticket|🎟️ admission tickets|🎪 circus|🎭 theater drama|🎨 art palette paint|🎬 clapper movie film|🎤 microphone sing karaoke|🎧 headphones music|🎼 score music|🎹 piano keyboard
🥁 drum|🎷 saxophone|🎺 trumpet|🎸 guitar|🎻 violin|🎲 dice game|♟️ chess|🎯 bullseye target dart|🎳 bowling|🎮 video game controller|🕹️ joystick|🧩 puzzle|🎉 party popper tada celebrate|🎊 confetti
🎈 balloon|🎁 gift present|🎀 ribbon bow|🎄 christmas tree|🎃 jack o lantern halloween|🎆 fireworks|🎇 sparkler|🧨 firecracker|🎐 wind chime|🎏 carp streamer|🎑 moon viewing|🧧 red envelope`],
  ['travel', 'Travel & places', '✈️', `
🚗 car|🚕 taxi|🚙 suv|🚌 bus|🚎 trolleybus|🏎️ race car|🚓 police car|🚑 ambulance|🚒 fire engine|🚐 minibus|🛻 pickup truck|🚚 delivery truck|🚜 tractor|🏍️ motorcycle
🛵 scooter|🚲 bicycle bike|🛴 kick scooter|🚨 siren|🚔 police car|🚍 bus|🚘 car|🚖 taxi|🚡 tram|🚠 cable car|🚟 railway|🚃 railway car|🚋 tram|🚝 monorail|🚄 bullet train
🚅 high speed train|🚈 light rail|🚂 locomotive steam train|🚆 train|🚇 metro subway|✈️ airplane plane flight travel|🛫 departure takeoff|🛬 arrival landing|🚁 helicopter|🚀 rocket launch|🛸 ufo|⛵ sailboat|🚤 speedboat|🛳️ cruise ship
⚓ anchor|⛽ fuel gas|🚧 construction|🚦 traffic light|🗺️ map|🗿 moai|🗽 statue of liberty|🗼 tokyo tower|🏰 castle|🏯 japanese castle|🏟️ stadium|🎡 ferris wheel|🎢 roller coaster|🎠 carousel
⛲ fountain|⛱️ beach umbrella|🏖️ beach|🏝️ island|🏜️ desert|🌋 volcano|⛰️ mountain|🏔️ snow mountain|🗻 mount fuji|🏕️ camping|⛺ tent|🏠 house home|🏡 house garden|🏢 office building
🏥 hospital|🏦 bank|🏨 hotel|🏪 convenience store|🏫 school|🏛️ classical building|⛪ church|🕌 mosque|🛕 hindu temple|🕍 synagogue|🌃 night city|🌆 cityscape dusk|🌅 sunrise|🌄 sunrise mountains|🌉 bridge night`],
  ['objects', 'Objects', '💡', `
⌚ watch|📱 phone mobile|💻 laptop computer|⌨️ keyboard|🖥️ desktop computer|🖨️ printer|🖱️ mouse|💾 floppy disk save|💿 cd|📷 camera|📸 camera flash photo|📹 video camera|🎥 movie camera|📞 telephone
☎️ phone|📺 tv television|📻 radio|🎙️ studio microphone podcast|⏰ alarm clock|⌛ hourglass|⏳ hourglass flowing|📡 satellite antenna|🔋 battery|🔌 plug|💡 light bulb idea|🔦 flashlight|🕯️ candle|💸 money wings
💵 dollar|💰 money bag|💳 credit card|💎 gem diamond|⚖️ scales justice|🧰 toolbox|🔧 wrench|🔨 hammer|⚒️ hammer pick|🛠️ tools|🔩 nut bolt|⚙️ gear settings|🧱 brick|⛓️ chains|🧲 magnet
🔫 water pistol|💣 bomb|🔪 knife|🗡️ dagger|🛡️ shield|🚬 cigarette|⚰️ coffin|🔮 crystal ball|📿 prayer beads|💈 barber|🔭 telescope|🔬 microscope|🩹 bandage|💊 pill medicine|💉 syringe vaccine
🩺 stethoscope doctor|🚪 door|🛏️ bed|🛋️ couch|🚽 toilet|🚿 shower|🛁 bathtub|🧴 lotion|🧹 broom|🧺 basket|🧻 toilet paper|🧼 soap|🛒 shopping cart|🎈 balloon|✉️ envelope mail email
📩 incoming envelope|📨 incoming mail|📧 email|📦 package box|📫 mailbox|📝 memo note write|📄 document page|📅 calendar date|📆 calendar|🗓️ spiral calendar|📇 card index|📈 chart up increase|📉 chart down decrease|📊 bar chart
📋 clipboard|📌 pushpin pin|📍 round pushpin location|📎 paperclip attach|✂️ scissors|🗑️ wastebasket trash|🔒 lock locked secure|🔓 unlocked|🔏 lock pen|🔐 lock key|🔑 key|🗝️ old key|📚 books|📖 open book read|🔖 bookmark
🏷️ label tag|📰 newspaper|🎓 graduation cap|👓 glasses|🕶️ sunglasses|👔 necktie|👕 t-shirt|👖 jeans|👗 dress|👙 bikini|👠 high heel|👟 sneaker|👑 crown king queen|🎒 backpack|💼 briefcase work`],
  ['symbols', 'Symbols', '❤️', `
✅ check mark done yes|☑️ ballot check|✔️ check|❌ cross no wrong|❎ cross button|➕ plus|➖ minus|➗ divide|✖️ multiply|❓ question|❔ white question|❗ exclamation|❕ white exclamation|‼️ double exclamation
⁉️ interrobang|〰️ wavy dash|⚠️ warning|🚫 prohibited no|⛔ no entry|🔞 18 adult|📵 no phones|🚭 no smoking|♻️ recycle|💤 zzz|🆗 ok button|🆕 new|🆓 free|🆙 up|🆒 cool|🆘 sos help|🔝 top|🔜 soon
🔛 on|🔚 end|🔙 back|➡️ right arrow|⬅️ left arrow|⬆️ up arrow|⬇️ down arrow|↗️ up right|↘️ down right|↙️ down left|↖️ up left|↕️ up down|↔️ left right|🔄 refresh repeat|🔃 clockwise
🔀 shuffle|🔁 repeat|🔂 repeat one|▶️ play|⏸️ pause|⏹️ stop|⏺️ record|⏭️ next|⏮️ previous|⏩ fast forward|⏪ rewind|🔼 up button|🔽 down button|🔅 dim|🔆 bright|🔇 mute|🔈 speaker|🔉 speaker medium|🔊 loud speaker
🔔 bell notification|🔕 bell off|📣 megaphone|📢 loudspeaker announce|🎵 music note|🎶 music notes|💲 dollar sign|💱 currency exchange|©️ copyright|®️ registered|™️ trademark|#️⃣ hash|*️⃣ asterisk|0️⃣ zero|1️⃣ one|2️⃣ two
3️⃣ three|4️⃣ four|5️⃣ five|6️⃣ six|7️⃣ seven|8️⃣ eight|9️⃣ nine|🔟 ten|🔴 red circle|🟠 orange circle|🟡 yellow circle|🟢 green circle|🔵 blue circle|🟣 purple circle|⚫ black circle|⚪ white circle|🟥 red square|🟩 green square
🟦 blue square|⬛ black square|⬜ white square|🔶 orange diamond|🔷 blue diamond|🔺 red triangle up|🔻 red triangle down|💠 diamond dot|🔘 radio button|♈ aries|♉ taurus|♊ gemini|♋ cancer|♌ leo|♍ virgo|♎ libra|♏ scorpio
♐ sagittarius|♑ capricorn|♒ aquarius|♓ pisces|☮️ peace|✝️ cross|☪️ star crescent|🕉️ om|☯️ yin yang|✡️ star of david|♾️ infinity|🏧 atm|🚾 wc toilet|♿ wheelchair|🅿️ parking|🛗 elevator|🚻 restroom`],
  ['flags', 'Flags', '🏁', `
🏁 chequered flag finish race|🚩 red flag|🎌 crossed flags|🏴 black flag|🏳️ white flag|🏳️‍🌈 rainbow flag pride|🏴‍☠️ pirate flag|🇺🇳 united nations|🇪🇺 european union eu
🇺🇸 united states usa america|🇬🇧 united kingdom uk britain|🇨🇦 canada|🇦🇺 australia|🇳🇿 new zealand|🇮🇪 ireland|🇫🇷 france|🇩🇪 germany|🇮🇹 italy|🇪🇸 spain|🇵🇹 portugal|🇳🇱 netherlands|🇧🇪 belgium|🇨🇭 switzerland
🇦🇹 austria|🇸🇪 sweden|🇳🇴 norway|🇩🇰 denmark|🇫🇮 finland|🇵🇱 poland|🇺🇦 ukraine|🇷🇺 russia|🇹🇷 turkey|🇬🇷 greece|🇮🇱 israel|🇸🇦 saudi arabia|🇦🇪 united arab emirates uae|🇪🇬 egypt
🇿🇦 south africa|🇳🇬 nigeria|🇰🇪 kenya|🇧🇷 brazil|🇦🇷 argentina|🇲🇽 mexico|🇨🇱 chile|🇨🇴 colombia|🇵🇪 peru|🇮🇳 india|🇵🇰 pakistan|🇧🇩 bangladesh|🇱🇰 sri lanka|🇳🇵 nepal
🇨🇳 china|🇯🇵 japan|🇰🇷 south korea|🇹🇼 taiwan|🇭🇰 hong kong|🇸🇬 singapore|🇲🇾 malaysia|🇮🇩 indonesia|🇹🇭 thailand|🇻🇳 vietnam|🇵🇭 philippines|🇲🇲 myanmar burma|🇰🇭 cambodia|🇱🇦 laos`],
]

export const EMOJI_CATEGORIES: EmojiCategory[] = RAW.map(([id, label, icon, entries]) => ({
  id, label, icon,
  emojis: entries.split(/[|\n]/).map((e) => e.trim()).filter(Boolean).map((e) => {
    const sp = e.indexOf(' ')
    return sp < 0 ? { char: e, keywords: '' } : { char: e.slice(0, sp), keywords: e.slice(sp + 1).toLowerCase() }
  }),
}))

const ALL = EMOJI_CATEGORIES.flatMap((c) => c.emojis.map((e) => ({ ...e, category: c.label.toLowerCase() })))

/** Case-insensitive search: every query word must prefix-match a keyword (or the category name). */
export function searchEmoji(query: string, limit = 120): string[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (words.length === 0) return []
  const out: string[] = []
  const seen = new Set<string>()
  for (const e of ALL) {
    const hay = `${e.keywords} ${e.category}`.split(/\s+/)
    if (words.every((w) => hay.some((h) => h.startsWith(w))) && !seen.has(e.char)) {
      seen.add(e.char)
      out.push(e.char)
      if (out.length >= limit) break
    }
  }
  return out
}

/** Inserts `insert` replacing the [start, end) selection; returns the new text and caret position. */
export function insertAt(text: string, insert: string, start: number, end: number, maxLength = Infinity) {
  const s = Math.max(0, Math.min(start, text.length))
  const e = Math.max(s, Math.min(end, text.length))
  const next = text.slice(0, s) + insert + text.slice(e)
  if (next.length > maxLength) return { text, caret: e }
  return { text: next, caret: s + insert.length }
}

// ------------------------------------------------------------------ recently used (per browser, local only)

const RECENT_KEY = 'securechat.recentEmoji'
const RECENT_MAX = 32

export function loadRecentEmoji(storage: Pick<Storage, 'getItem'> | undefined = globalThis.localStorage): string[] {
  try {
    const v = JSON.parse(storage?.getItem(RECENT_KEY) ?? '[]')
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string').slice(0, RECENT_MAX) : []
  } catch {
    return []
  }
}

export function pushRecentEmoji(char: string, storage: Pick<Storage, 'getItem' | 'setItem'> | undefined = globalThis.localStorage): string[] {
  const next = [char, ...loadRecentEmoji(storage).filter((c) => c !== char)].slice(0, RECENT_MAX)
  try {
    storage?.setItem(RECENT_KEY, JSON.stringify(next))
  } catch {
    /* storage full / disabled: recents just won't persist */
  }
  return next
}
