
import re

# --- ROBUST ROOTS (Substring Matching - No Boundaries Needed) ---
ROBUST_ROOTS = {
    # Telugu Tens - Latin
    "thombai": "9", "tombai": "9", "thombhai": "9", "tombhai": "9", "thumbai": "9",
    "enabhai": "8", "enabai": "8", "enbai": "8", "yenabhai": "8", "yenbhai": "8",
    "debbai": "7", "debai": "7", "debbhai": "7", "dabbai": "7",
    "aravai": "6", "arvai": "6", "arabhai": "6", "arubhai": "6",
    "yabhai": "5", "yabai": "5", "yavai": "5", "yaabhai": "5",
    "nalabhai": "4", "nalabai": "4", "nalbai": "4", "nallabai": "4",
    "muppai": "3", "mupai": "3", "mupbai": "3",
    "iravai": "2", "iruvai": "2", "irvai": "2", "yiravai": "2",

    # Telugu Tens - Devanagari (Aggressive)
    "तोम्बई": "9", "तोमबाई": "9", "थोम्बई": "9", "थोमबाई": "9", "तुम भाई": "9", "तो मयी": "9", "तो माई": "9",
    "एनभई": "8", "येनभई": "8", "एन भाई": "8", "येन भाई": "8", "एन बई": "8",
    "देब्बई": "7", "देब्बै": "7", "दब्बई": "7", "दे भाई": "7", "दे बई": "7", "द भाई": "7",
    "अरवई": "6", "अरभाई": "6", "अर भाई": "6", "आ रहा भाई": "6", "अर बई": "6",
    "यभई": "5", "याभई": "5", "या भाई": "5", "यह भाई": "5", "या बई": "5",
    "नलभई": "4", "नलभाई": "4", "नल भाई": "4", "नल बई": "4",
    "मुप्पई": "3", "मुपई": "3", "मुँह पाई": "3", "मुबई": "3",
    "इरवई": "2", "इरावाई": "2", "इर भाई": "2", "इर बई": "2",

    # Specific Compound Fixes
    "मलगु": "4", # Malgu -> Nalugu (4)
    "आलू": "6",  # Aalu -> Aaru (6)
    "पेड़ू": "7", # Pedu -> Yedu (7)
    "मोडू": "3", # Modu -> Moodu (3)
    "रंडू": "2", # Randu -> Rendu (2)
    "ओकटी": "1", # Okati -> 1
    "सुनारण्डु": "02", # Suna Rendu
}

# --- PHONETIC MAP (Whole Word Matching) ---
PHONETIC_MAP = {
    # Telugu Single Digits & Small Numbers (Latin)
    "okati": "1", "okate": "1", "okuti": "1",
    "rendu": "2", "rendoo": "2", "rondu": "2",
    "moodu": "3", "mudu": "3", "mood": "3",
    "nalugu": "4", "nalgu": "4",
    "aidu": "5", "aidhu": "5",
    "aaru": "6", "aru": "6", 
    "edu": "7", "yedu": "7", "yedu": "7",
    "enimidi": "8", "yenimidi": "8", "enimidhi": "8",
    "tommidi": "9", "thommidthi": "9", "thommidi": "9", "tommidhi": "9",
    "padi": "1", "padhi": "1",
    "vanda": "1", "vandha": "1", "wanda": "1",
    "sunna": "0", 

    # Telugu in Devanagari (Single Digits)
    "ओकटी": "1", "ओकटे": "1", "रेंडु": "2", "रेंडू": "2", "मूड": "3", "मूडु": "3", "मोह": "3",
    "नालुगु": "4", "नालुगू": "4", "ऐदु": "5", "आरु": "6", "आरू": "6", 
    "एडु": "7", "एनिमिदी": "8", "एनिमिदि": "8", "तोमिदि": "9", "तोम्मिदि": "9",
    "सुन्ना": "0", "पदि": "1", "वंद": "1",

    # Hindi (Latin Script)
    "shunya": "0", "zero": "0", "ek": "1", "do": "2", "teen": "3", 
    "chaar": "4", "char": "4", "paanch": "5", "che": "6", "chah": "6",
    "saat": "7", "aath": "8", "nau": "9", "das": "1",
    "gyarah": "1", "barah": "1", "saau": "1",
    "bees": "2", "tees": "3", "chalees": "4", "pachaas": "5", "saath": "6", "sattar": "7", "assi": "8", "nabbe": "9", "sau": "1",

    # Hindi (Devanagari Script)
    "शून्य": "0", "एक": "1", "दो": "2", "तीन": "3", "चार": "4", "पांच": "5", "छह": "6", "सात": "7", "आठ": "8", "नौ": "9", "दस": "1",
    "ग्यारह": "1", "बारह": "1",
    "रोमीदी": "9", "नगलू": "4", "रोमीदो": "2", "सममा": "0", "नालु": "4",
    "बीस": "2", "तीस": "3", "चालीस": "4", "पचास": "5", "साठ": "6", "sath": "6", "सत्तर": "7", "अस्सी": "8", "नब्बे": "9", "सौ": "1",

    # English Numbers
    "one": "1", "two": "2", "three": "3", "four": "4",
    "five": "5", "six": "6", "seven": "7", "eight": "8", "nine": "9",
    "ten": "1", "eleven": "1", "twelve": "1", "thirteen": "1", "fourteen": "1", "fifteen": "1", "sixteen": "1", "seventeen": "1", "eighteen": "1", "nineteen": "1",
    "twenty": "2", "thirty": "3", "forty": "4", "fifty": "5", "sixty": "6", "seventy": "7", "eighty": "8", "ninety": "9", "hundred": "1",
    "single": "1", "double": "2", "triple": "3",

    # English in Devanagari
    "वन": "1", "टू": "2", "थ्री": "3", "फोर": "4", "फाइव": "5", "सिक्स": "6", "सेवन": "7", "एट": "8", "नाइन": "9", "टेन": "1",
    "इलेवन": "1", "ट्वेल": "1", "थर्टीन": "1", "फोर्टिन": "1", "fifteen": "1", "sixteen": "1", "seventeen": "1", "eighteen": "1", "nineteen": "1",
    "ट्वेंटी": "2", "थर्टी": "3", "फोर्टी": "4", "फिफ्टी": "5", "सिक्सटी": "6", "सेवेंटी": "7", "एट्टी": "8", "नाइंटी": "9", "हंड्रेड": "1",
    "जीरो": "0", "सिंगल": "1", "डबल": "2", "ट्रिपल": "3",
}

def normalize_text(text):
    if not text: return ""
    t = text.lower()
    
    # 1. Process ROBUST ROOTS first (Substring match - No word boundaries)
    for word, digit in ROBUST_ROOTS.items():
        t = t.replace(word, digit)

    # 2. Process Standard PHONETIC MAP (Whole word match)
    for word, digit in PHONETIC_MAP.items():
        t = re.sub(r'\b' + re.escape(word) + r'\b', digit, t)
        
    return t

test_cases = [
    "Na number Thombai Enimidi",
    "thombaienimidi",
    "tom bai enimidi",
    "tum bhai enimidi",
    "debbai aaru",
    "de bhai aru",
    "yabhai nalugu",
    "ya bhai nalgu",
    "na number okati rendu moodu",
    "okati rendu moodu",
    "one two three"
]

print("--- TESTING NORMALIZER ---")
for tc in test_cases:
    norm = normalize_text(tc)
    digits = "".join(re.findall(r'\d', norm))
    print(f"Input: {tc} | Output: {norm} | Digits: {digits}")
