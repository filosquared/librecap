import base64
import hashlib
import json
import math
import os
import platform
import random
import re
import string
import argparse
import secrets
import sys
from collections.abc import Mapping
from pathlib import Path
from cryptography.fernet import Fernet
from aiohttp import web
from .security import hash_password
from .storage import SQLiteStore
from .wizard import setup_wizard

# Candy
welcomes = ["Hello", "Hi", "Hey"]
greetings = ["How are you doing?", "Good to see you again.", "How are things?", "Librusik is awesome, isn't it?", "Too lazy to log into Synergia? :D", "Have a wonderful day!", "Nice to see you.", "Synergia still sucks? :D"]


# Data location. Resolve bundled assets from the executable when frozen, while
# keeping packaged desktop runtime data in a protected per-user directory.
if getattr(sys, "frozen", False):
	BASE_DIR = Path(getattr(sys, "_MEIPASS", Path(sys.executable).resolve().parent))
else:
	BASE_DIR = Path(__file__).resolve().parent.parent
PATH = str(BASE_DIR)


def default_data_dir(
	base_dir: Path,
	*,
	frozen: bool,
	platform_name: str,
	home: Path,
	environ: Mapping[str, str],
) -> Path:
	"""Return the default writable data directory for a runtime environment."""
	override = environ.get("LIBRECAP_DATA_DIR")
	if override:
		return Path(override)
	if frozen and platform_name == "darwin":
		return home / "Library" / "Application Support" / "LibreCap"
	if frozen and platform_name == "win32":
		local_app_data = environ.get("LOCALAPPDATA")
		root = Path(local_app_data) if local_app_data else home / "AppData" / "Local"
		return root / "LibreCap"
	return base_dir / "data"


DEFAULT_DATA_DIR = str(
	default_data_dir(
		BASE_DIR,
		frozen=getattr(sys, "frozen", False),
		platform_name=sys.platform,
		home=Path.home(),
		environ=os.environ,
	)
)
DATA_DIR = DEFAULT_DATA_DIR
PROFILE_PIC_DIR = os.path.join(DATA_DIR, "profile_pics")
STORE = SQLiteStore(DATA_DIR)
INITIAL_ADMIN_PASSWORD = None


# Some constant globals
ERR_403 = "Couldn't fetch data from Synergia."
ERR_500 = "Server wasn't able to parse this request."

# Some dynamic globals
LAST_SEEN_PEPS = {}


# Host information
uname = platform.uname()
host = {
	"os": uname.system,
	"node": uname.node,
	"version": platform.python_version(),
	"cpus": os.cpu_count()
}


# Core functions
def setup(CONFIG_DEFAULT):
	global INITIAL_ADMIN_PASSWORD
	INITIAL_ADMIN_PASSWORD = None
	data_path = Path(DATA_DIR)
	first_run = not data_path.exists() or not any(data_path.glob("*.sqlite3"))
	parser = argparse.ArgumentParser()
	parser.add_argument("--skip-wizard", action="store_true", default=False, help="Skip setup wizard on first run")
	args, _ = parser.parse_known_args()
	skip_wizard = args.skip_wizard or os.environ.get("LIBRECAP_SKIP_WIZARD") == "1"

	config, database = STORE.load(CONFIG_DEFAULT)
	if first_run and not skip_wizard:
		ip, port = setup_wizard()
		config["listen_address"] = ip
		config["port"] = port
	elif first_run:
		print("Seems it is the first run. Initializing data...")

	# Never ship a reusable admin/admin credential. New installations receive a
	# random one-time credential in the process output, which the owner should
	# change after first login.
	legacy_admin_hash = hashlib.sha256(b"admin").hexdigest()
	if config.get("passwd") in (None, "", legacy_admin_hash):
		initial_password = secrets.token_urlsafe(18)
		INITIAL_ADMIN_PASSWORD = initial_password
		config["passwd"] = hash_password(initial_password)
		print("Initial panel credentials")
		print(f"  username: {config.get('name', 'admin')}")
		print(f"  password: {initial_password}")
		print("Change this password after signing in.")

	data_path.mkdir(parents=True, exist_ok=True)
	Path(PROFILE_PIC_DIR).mkdir(parents=True, exist_ok=True)
	if os.environ.get("LIBRECAP_LISTEN_ADDRESS"):
		config["listen_address"] = os.environ["LIBRECAP_LISTEN_ADDRESS"]
	if os.environ.get("LIBRECAP_PORT"):
		config["port"] = int(os.environ["LIBRECAP_PORT"])
	if os.environ.get("LIBRECAP_CHECK_BROWSER") is not None:
		config["check_browser"] = os.environ["LIBRECAP_CHECK_BROWSER"].lower() not in {"0", "false", "no"}
	STORE.save_config(config)
	STORE.save_users(database)

	key_path = data_path / "fernet.key"
	if not key_path.exists():
		key_path.write_bytes(Fernet.generate_key())
	try:
		os.chmod(key_path, 0o400)
	except OSError:
		pass
	load_encryption_keys()
	return (config, database)

def load_html_resources(config):
	html_dir = BASE_DIR / "html"
	return {
		key: (html_dir / filename).read_text(encoding="utf-8")
		for key, filename in {
			"index": "index.html", "home": "home.html", "grades": "grades.html",
			"more": "more.html", "timetable": "timetable.html", "messages": "messages.html",
			"message": "message.html", "attendances": "attendances.html",
			"attendancesold": "attendancesold.html", "exams": "exams.html",
			"freedays": "freedays.html", "teacherfreedays": "teacherfreedays.html",
			"parentteacherconferences": "parentteacherconferences.html", "school": "school.html",
			"settings": "settings.html", "login": "login.html", "about": "about.html",
			"tiers": "tiers.html", "panel": "panel.html", "panellogin": "panellogin.html",
			"error": "error.html", "errorpage": "geterror.html"
		}.items()
	}


# Encryption & passwords
frt = None
def load_encryption_keys():
	global frt
	key = Path(DATA_DIR, "fernet.key").read_text(encoding="utf-8")
	frt = Fernet(key.encode())

def encrypt(what):
	coded = frt.encrypt(what.encode())
	return coded.decode()

def decrypt(what):
	coded = frt.decrypt(what.encode())
	return coded.decode()

def sha(what):
	return hashlib.sha256(what.encode()).hexdigest()

def randompasswd():
	return "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(8))


# Grade helper functions
def gradeValue(ocen):
	if ocen[:1] in ["1", "2", "3", "4", "5", "6"]:
		if "+" in ocen:
			return int(ocen[:1]) + 0.5
		elif "-" in ocen:
			return int(ocen[:1]) - 0.25
		else:
			return int(ocen)
	return 0

def valueGrade(ocen):
	if ".5" in ocen:
		return "%s+" % ocen[:1]
	elif ".75" in ocen:
		return "%s-" % ocen[:1]
	else:
		return ocen

def predictAverage(hmm):
	fullgrade = math.floor(hmm)
	if hmm >= fullgrade + 0.75:
		return fullgrade + 1
	if hmm >= fullgrade + 0.5:
		return fullgrade + 0.5
	return fullgrade


# aiohttp wrappers
def response(text, code):
	return web.Response(text = text, status = code, headers = {'Content-Type': 'text/html'})

def JSONresponse(text, code):
	return web.Response(text = json.dumps(text), status = code, headers = {'Content-Type': 'application/json'})


# Some helper functions
def checklen(string, minlen, maxlen):
	s = len(string)
	return minlen <= s <= maxlen

def parseDumbs(strink):
	strink = strink.replace("&", "&amp;")
	strink = strink.replace("<", "&lt;")
	strink = strink.replace(">", "&gt;")
	strink = strink.replace("\n", "<br>")
	return strink

def linkify(html_str):
	html_str = html_str.replace("\n", "<br>")
	unmess = " ".join(html_str.replace(").", ") .").split())
	unmess = re.sub(r"\[[^]]*\]", lambda x: x.group(0).replace(' ','&nbsp;'), unmess)
	words = []
	for word in unmess.split(" "):
		if word.startswith("[") and word.endswith(")"):
			name = word.split("[")[1].split("]")[0]
			url = word.split("(")[1].split(")")[0]
			word = """<a href="%s" target="_blank">%s</a>""" % (url, name)
		elif "://" in word:
			nicer = word.split("://")[1]
			word = """<a href="%s" target="_blank">%s</a>""" % (word, nicer)
		words.append(word)
	return " ".join(words).replace("</a> .", "</a>.")

def parseContact(contact):
	if "://" not in contact:
		contact = "mailto:" + contact
	return contact


# Mess
def mktryagainbtn(location, number):
	return """<button onclick="goto('%s', %s, 'true')" class="highlighted">Try again</button><br>""" % (location, number)

def mkbackbtn(location, number):
	return """<button class="back" onclick="goto('%s', %s, true, true)"></button><br>""" % (location, number)

def tierror_(REQ_TIER, backpath, button, where):
	if where:
		button = "<button onclick=\"goto('" + where + "', 2, true)\">" + button + "</button>"
	else:
		button = ""
	if not backpath:
		backpath = ""
	else:
		backpath = mkbackbtn(backpath, 2)
	return (backpath, "Feature unavailable", "This feature is available in <div class=\"tier " + REQ_TIER + "\"></div> tier.", "<button onclick=\"goto('settings', 3, true)\" class=\"highlighted\">Upgrade tier</button>" + button)

def copyable_tr(_tr):
	"""Log diagnostic details and return a safe public error message."""
	if _tr:
		print(_tr, end="")
	return "The server couldn't complete this request. Please try again later."
