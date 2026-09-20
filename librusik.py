import asyncio
import json
import math
import os
import random
import re
import ssl
import time
import traceback
import uuid
from datetime import datetime, date, timedelta
from glob import glob
from aiohttp import web
from lib.api import librus
from lib.api import Librus
from lib.api import SessionManager
from lib.desktop import build_app_url
from lib.security import AuthSessionManager, hash_password, needs_rehash, verify_password
from lib.utils import *
from shutil import copytree
import atexit

# szczerze nie ma mam zielonego pojęca czemu to tutaj jest xD
# cwd = os.getcwd()
# if (os.path.isdir("./data")):
# 	copytree("./data", os.path.join(os.path.dirname(__file__), "data"), dirs_exist_ok=True)
# os.chdir(os.path.dirname(__file__))
# atexit.register(lambda: copytree("./data", os.path.join(cwd, "data")), dirs_exist_ok=True)
# obejście tego że pyinstaller robi nieśmieszne (odpala w temp środowisku)

CONFIG_DEFAULT = {
	"enable_registration": False,
	"max_users": 8,
	"name": "admin",
	"passwd": None,
	"port": 7777,
	"listen_address": "127.0.0.1",
	"subdirectory": "/",
	"readable_db": False,
	"notice": None,
	"check_browser": True,
	"ssl": False,
	"pubkey": "/etc/letsencrypt/live/my.domain.com/fullchain.pem",
	"privkey": "/etc/letsencrypt/live/my.domain.com/privkey.pem",
	"contact_uri": "librusik@my.domain",
	"enable_tiers": False,
	"tiers_text": "Tiers are enabled to prevent random people using this instance.",
	"tiers_requirements": {
		"free": "To get, say thank you.",
		"plus": "To get, buy me a beer.",
		"pro": "To get, solve the [puzzle](https://www.youtube.com/watch?v=dQw4w9WgXcQ)."
	},
	"debug": False
}


config, database = setup(CONFIG_DEFAULT)
resources = load_html_resources(config)
ACTIVE_PORT = None
LIBRUSIK_PATH = os.path.dirname(os.path.abspath(__file__)) + "/"
SESSIONS = SessionManager(database)
AUTH_SESSIONS = AuthSessionManager()
PANEL_SESSIONS = AuthSessionManager()
USER_SESSION_COOKIE = "librecap_session"
PANEL_SESSION_COOKIE = "librecap_admin_session"
BOOT = round(time.time())
welcome = welcomes[0]
greeting = greetings[0]


def local_url() -> str:
	"""Return the URL for the currently running local server."""
	return build_app_url(config, port=ACTIVE_PORT)


async def updatetitles():
	global welcome
	global greeting
	week_last = datetime.now().strftime("%W")
	while True:
		week = datetime.now().strftime("%W")
		if week != week_last:
			week_last = week
			librus.REQUESTS = [0] * 7
		welcome = random.choice(welcomes)
		greeting = random.choice(greetings)
		await asyncio.sleep(60)


def updatedb():
	STORE.save_users(database)

def updateconf():
	STORE.save_config(config)

def auth(data, request):
	"""Authenticate a request using the server-side session cookie."""
	username = AUTH_SESSIONS.get_subject(request.cookies.get(USER_SESSION_COOKIE))
	if username not in database:
		return False
	if isinstance(data, dict):
		data["username"] = username
	return True


def session_username(request):
	username = AUTH_SESSIONS.get_subject(request.cookies.get(USER_SESSION_COOKIE))
	return username if username in database else None


def panel_auth(request):
	return PANEL_SESSIONS.get_subject(request.cookies.get(PANEL_SESSION_COOKIE)) == "admin"


def confirm_password(data, username):
	return verify_password(data.get("current_password"), database[username]["passwd"])


def set_session_cookie(resp, name, token, request):
	resp.set_cookie(
		name,
		token,
		max_age=28 * 24 * 60 * 60,
		httponly=True,
		secure=request.secure,
		samesite="Lax",
		path="/",
	)


def clear_session_cookie(resp, name):
	resp.del_cookie(name, path="/")


TIERS = ["demo", "free", "plus", "pro"]
def check_tier(user, required_tier):
	if config["enable_tiers"]:
		if database[user]["tier"] == "demo":
			return demo_has_access(user)
		required = TIERS.index(required_tier)
		current = TIERS.index(database[user]["tier"])
		return True if required <= current else False
	else:
		return True

def demoleft(user):
	if database[user]["tier"] == TIERS[0] and config["enable_tiers"]:
		joined = datetime.strptime(database[user]["joined"], '%d %b %Y')
		now = datetime.now()
		diff = 7 - (now - joined).days
		return 0 if diff <= 0 else diff
	return -1

def tierror(REQ_TIER, backpath, button, where):
	return resources["error"] % tierror_(REQ_TIER, backpath, button, where)

def tierror_resp(REQ_TIER, backpath, button, where):
	return response(tierror(REQ_TIER, backpath, button, where), 700)

def demo_has_access(user):
	return not demoleft(user) == 0

def demo_err(user):
	if not demo_has_access(user):
		return tierror_resp("free", False, False, False)
	return False


async def mkaccount(data):
	global database
	if config["max_users"] <= len(database):
		return "Database is full."
	if data["username"] in database:
		return "Account with provided username already exists!"
	for x in database:
		if data["librusLogin"] == database[x]["l_login"]:
			return "Provided login is already in use."
	librus = Librus(SESSIONS.get(data["username"]))
	if await librus.mktoken(data["librusLogin"], data["librusPassword"]):
		userInfo = await librus.get_me()
		if userInfo == None:
			return "error"
		if config["max_users"] <= len(database):
			return "Database is full."
		if data["username"] in database:
			return "Account with provided username already exists!"
		for x in database:
			if data["librusLogin"] == database[x]["l_login"]:
				return "Provided login is already in use."
		database[data["username"]] = {}
		database[data["username"]]["first_name"] = userInfo["FirstName"]
		database[data["username"]]["last_name"] = userInfo["LastName"]
		database[data["username"]]["passwd"] = hash_password(data["password"])
		database[data["username"]]["l_login"] = data["librusLogin"]
		database[data["username"]]["l_passwd"] = encrypt(data["librusPassword"])
		database[data["username"]]["year_starts"] = userInfo["SchoolYearStarts"]
		database[data["username"]]["year_ends"] = userInfo["SchoolYearEnds"]
		database[data["username"]]["custom_pic"] = None
		database[data["username"]]["grades_cleanup"] = False
		database[data["username"]]["attendances_cleanup"] = False
		database[data["username"]]["confetti"] = False
		database[data["username"]]["tier"] = TIERS[0]
		database[data["username"]]["joined"] = datetime.now().strftime('%d %b %Y')
		timgs = glob(str(BASE_DIR / "static/img/profile/*"))
		timg = []
		for x in timgs:
			if not x.endswith("/custom"):
				timg.append(x)
		timg = random.choice(timg)
		database[data["username"]]["profile_pic"] = os.path.basename(timg)
		updatedb()
		SESSIONS.updatedb(database)
		return True
	return "Wrong Synergia credentials."

async def scaccount(data):
	global database
	for x in database:
		if data["librusLogin"] == database[x]["l_login"] and x != data["username"]:
			return "Provided login is already in use."
	librus = Librus(SESSIONS.get(data["username"]))
	if await librus.mktoken(data["librusLogin"], data["librusPassword"]):
		userInfo = await librus.get_me()
		if userInfo == None:
			return False
		for x in database:
			if data["librusLogin"] == database[x]["l_login"] and x != data["username"]:
				return "Provided login is already in use."
		database[data["username"]]["first_name"] = userInfo["FirstName"]
		database[data["username"]]["last_name"] = userInfo["LastName"]
		database[data["username"]]["l_login"] = data["librusLogin"]
		database[data["username"]]["l_passwd"] = encrypt(data["librusPassword"])
		database[data["username"]]["year_starts"] = userInfo["SchoolYearStarts"]
		database[data["username"]]["year_ends"] = userInfo["SchoolYearEnds"]
		updatedb()
		SESSIONS.updatedb(database)
		return True
	return "Wrong Synergia credentials."

async def api(request):
	global database, LAST_SEEN_PEPS
	try:
		data = await request.json()
		if not isinstance(data, dict):
			return response("", 400)
		if "method" in data and data["method"] in ["mkaccount", "delaccount", "chgpasswd", "chglibrus", "chglibruspasswd", "getstuff", "grades_cleanup", "attendances_cleanup", "confetti", "get_me", "get_notifications"]:
			method = data["method"]
			if method == "mkaccount":
				if not config["enable_registration"]:
					return response("", 401)
				if "username" in data and "password" in data and "librusLogin" in data and "librusPassword" in data:
					if isinstance(data["username"], str) and isinstance(data["password"], str) and isinstance(data["librusLogin"], str) and isinstance(data["librusPassword"], str):
						reg = re.compile("[a-z0-9]+")
						if not reg.fullmatch(data["username"]):
							return response("", 400)
						if checklen(data["username"], 4, 16) and checklen(data["password"], 4, 32):
							make = await mkaccount(data)
							if make == True:
								token = AUTH_SESSIONS.create(data["username"])
								resp = response("", 200)
								set_session_cookie(resp, USER_SESSION_COOKIE, token, request)
								return resp
							return response(make, 403)
				return response("", 400)
			elif auth(data, request):
				LAST_SEEN_PEPS[data["username"]] = int(time.time())
				if method == "chgpasswd":
					if "newpassword" in data and isinstance(data["newpassword"], str):
						if not checklen(data["newpassword"], 4, 32):
							return response("", 400)
						if not confirm_password(data, data["username"]):
							return response("", 401)
						database[data["username"]]["passwd"] = hash_password(data["newpassword"])
						updatedb()
						return response("", 200)
				elif method == "chglibruspasswd":
					if "newLibrusPassword" in data and isinstance(data["newLibrusPassword"], str):
						if not confirm_password(data, data["username"]):
							return response("", 401)
						data["librusLogin"] = database[data["username"]]["l_login"]
						data["librusPassword"] = data["newLibrusPassword"]
						make = await scaccount(data)
						if make == True:
							return response("", 200)
						return response(make, 403)
				elif method == "grades_cleanup":
					if "value" in data and isinstance(data["value"], bool):
						database[data["username"]]["grades_cleanup"] = data["value"]
						updatedb()
						return response("", 200)
				elif method == "get_me":
					return JSONresponse({
						"confetti": database[data["username"]]["confetti"],
						"tier": database[data["username"]]["tier"],
						"demoleft": demoleft(data["username"]),
						"contact": parseContact(config["contact_uri"])
					}, 200)
				elif method == "get_notifications":
					REQ_TIER = "pro"
					if check_tier(data["username"], REQ_TIER):
						librus = Librus(SESSIONS.get(data["username"]))
						if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
							notifications = await librus.get_notifications()
							if notifications is None:
								return response("", 503)
							return JSONresponse(SESSIONS.get_notifications(data["username"], notifications), 200)
						else:
							return response({}, 403)
					else:
						return response(REQ_TIER, 700)
				elif method == "confetti":
					if "value" in data and isinstance(data["value"], bool):
						database[data["username"]]["confetti"] = data["value"]
						updatedb()
						return response("", 200)
				elif method == "attendances_cleanup":
					if "value" in data and isinstance(data["value"], bool):
						database[data["username"]]["attendances_cleanup"] = data["value"]
						updatedb()
						return response("", 200)
				elif method == "chglibrus":
					if "newLibrusLogin" in data and "newLibrusPassword" in data and isinstance(data["newLibrusLogin"], str) and isinstance(data["newLibrusPassword"], str):
						if not confirm_password(data, data["username"]):
							return response("", 401)
						data["librusLogin"] = data["newLibrusLogin"]
						data["librusPassword"] = data["newLibrusPassword"]
						make = await scaccount(data)
						if make == True:
							return response("", 200)
						return response(make, 403)
				elif method == "getstuff":
					demo = demo_err(data["username"])
					if demo != False: return demo
					librus = Librus(SESSIONS.get(data["username"]))
					if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
						SESSIONS.save(data["username"], librus.headers)
						lucky = await librus.get_lucky_number()
						if not lucky:
							lucky = -1
						try:
							m = (await librus.get_data("Classes"))["Class"]
							if database[data["username"]]["year_ends"] != m["EndSchoolYear"] or database[data["username"]]["year_starts"] != m["BeginSchoolYear"]:
								database[data["username"]]["year_starts"] = m["BeginSchoolYear"]
								database[data["username"]]["year_ends"] = m["EndSchoolYear"]
								updatedb()
						except:
							pass
						return response(json.dumps({
							"messages": 0,
							"luckynum": lucky
						}), 200)
					return response("", 403)
				elif method == "delaccount":
					if not confirm_password(data, data["username"]):
						return response("", 401)
					if config["enable_tiers"] and database[data["username"]]["tier"] == "demo":
						return response("", 401)
					demo = demo_err(data["username"])
					if demo != False: return demo
					if data["username"] in LAST_SEEN_PEPS:
						del LAST_SEEN_PEPS[data["username"]]
					try:
						os.remove("%s/%s" % (PROFILE_PIC_DIR, database[data["username"]]["custom_pic"]))
					except FileNotFoundError:
						pass
					del database[data["username"]]
					updatedb()
					SESSIONS.updatedb(database)
					return response("", 200)
				return response("", 400)
			return response("", 401)
		return response("", 400)
	except Exception:
		traceback.print_exc()
		return response("Internal server error.", 500)

async def authenticate(request):
	try:
		data = await request.json()
		username = data.get("username") if isinstance(data, dict) else None
		password = data.get("password") if isinstance(data, dict) else None
		if username in database and verify_password(password, database[username]["passwd"]):
			if needs_rehash(database[username]["passwd"]):
				database[username]["passwd"] = hash_password(password)
				updatedb()
			token = AUTH_SESSIONS.create(username)
			resp = response("", 200)
			set_session_cookie(resp, USER_SESSION_COOKIE, token, request)
			return resp
		return response("", 401)
	except:
		return response("", 400)

async def session_status(request):
	username = AUTH_SESSIONS.get_subject(request.cookies.get(USER_SESSION_COOKIE))
	return response("", 200 if username in database else 401)

async def logout(request):
	AUTH_SESSIONS.revoke(request.cookies.get(USER_SESSION_COOKIE))
	resp = response("", 200)
	clear_session_cookie(resp, USER_SESSION_COOKIE)
	return resp

async def panel_session_status(request):
	return response("", 200 if panel_auth(request) else 401)

async def panel_logout(request):
	PANEL_SESSIONS.revoke(request.cookies.get(PANEL_SESSION_COOKIE))
	resp = response("", 200)
	clear_session_cookie(resp, PANEL_SESSION_COOKIE)
	return resp

async def forgot_pass(request):
	global database
	try:
		data = await request.json()
		librus = Librus(None)
		if await librus.mktoken(data["synergia_login"], data["synergia_password"]):
			for user in database:
				if database[user]["l_login"] == data["synergia_login"]:
					SESSIONS.save(user, librus.headers)
					newpass = randompasswd()
					database[user]["passwd"] = hash_password(newpass)
					updatedb()
					return JSONresponse({
						"username": user,
						"password": newpass
					}, 200)
			return response("", 400)
		return response("", 401)
	except:
		return response("", 400)


async def index(request):
	hidetiers = "" if config["enable_tiers"] else ".tiers{display:none !important}"
	return response(resources["index"] % (config["subdirectory"], hidetiers), 200)

async def login(request):
	show_register = "" if config["enable_registration"] else "display:none"
	abouts = resources["about"] % parseContact(config["contact_uri"])
	return response(resources["login"] % (show_register, abouts), 200)


####################################################################################################################################################################################################


async def home(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			now = datetime.now()
			scholstarts = datetime.strptime(database[data["username"]]["year_starts"], '%Y-%m-%d')
			scholends = datetime.strptime(database[data["username"]]["year_ends"], '%Y-%m-%d')
			dayspassed = (now - scholstarts).days
			daysleft = (scholends - now).days
			daystotal = daysleft + dayspassed
			percent = dayspassed * 100 / daystotal
			progbar = 220 - 2.2 * percent
			if progbar > 220:
				progbar = 220
			addon = "s"
			if daysleft == 1:
				addon = ""
			if daysleft <= 0:
				daysleft = "--"
			a = date(date.today().year, 1, 1)
			b = date(date.today().year, 12, 31)
			day_of_year = datetime.now().timetuple().tm_yday - 1
			ddaysleft = (b - a).days - day_of_year
			dpercent = day_of_year * 100 / (b - a).days
			dprogbar = 220 - 2.2 * dpercent
			if dprogbar > 220:
				dprogbar = 220
			daddon = "s"
			if ddaysleft == 1:
				daddon = ""
			showNotice = "" if config["notice"] else "display: none"
			return response(resources["home"] % (showNotice, linkify(str(config["notice"])), welcome, database[data["username"]]["first_name"], greeting, progbar, round(percent), daysleft, addon, dprogbar, round(dpercent), ddaysleft, daddon, progbar, dprogbar), 200)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % ("", "Internal server error", tr, mktryagainbtn("/home", 0)), 500)


async def grades(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				result = await librus.get_grades()
				grades = 0
				page = ""
				divz = ""
				rcnt = ""
				averages = ""
				allocens = []
				avgdict = {}
				for s in result:
					notshowempty = database[data["username"]]["grades_cleanup"] and result[s] == []
					if notshowempty:
						continue
					savgarr = []
					savg = ""
					page += """<button class="bubble" onclick="showdiv('main', '%s')"><div class="name">%s</div><div class="value"><code>""" % (s.lower(), s)
					sdivz = ""
					ocenk = ""
					startnewsmstr = False
					koniec = False
					for x in result[s]:
						grades += 1
						weight = ", weight %s" % x["Weight"]
						grd = gradeValue(x["Grade"])
						if weight == ", weight none":
							weight = ""
						else:
							if grd:
								for _ in range(0, x["Weight"]):
									savgarr.append(grd)
						ocenq = """<button class="bubble wide unclickable"><div class="name ocen">%s</div><div class="oceninfo"><div class="value"><b>%s</b>%s</div><div class="value">%s</div><div class="value"><i>%s</i></div><div class="value">Added by %s %s</div><div class="value">%s</div></div></button>""" % (x["Grade"], x["Category"].title(), weight, s, parseDumbs(x["Comment"]), x["AddedBy"]["FirstName"], x["AddedBy"]["LastName"], x["AddDate"])
						ocenk = """<button class="bubble wide unclickable"><div class="name ocen">%s</div><div class="oceninfo"><div class="value"><b>%s</b>%s</div><div class="value"><i>%s</i></div><div class="value">Added by %s %s</div><div class="value">%s</div></div></button>%s""" % (x["Grade"], x["Category"].title(), weight, parseDumbs(x["Comment"]), x["AddedBy"]["FirstName"], x["AddedBy"]["LastName"], x["AddDate"], ocenk)
						allocens.append({
							"string": ocenq,
							"AddDate": (datetime.strptime(x["AddDate"], '%Y-%m-%d %H:%M:%S') - datetime(1970, 1, 1)).total_seconds()
						})
						if x["underlined"]:
							page += "<b><u>%s</u></b><br>" % x["Grade"]
						else:
							page += ("%s " % x["Grade"])
						if x["separator"]:
							ocenk = """<div class="marker">Previous semester</div>%s""" % ocenk
						else:
							startnewsmstr = True
						if (x["isSemester"] and (result[s].index(x) + 1) == len(result[s])) or x["isFinal"]:
							koniec = True
							if grd:
								avgdict[s] = grd
					if startnewsmstr:
						ocenk = """<div class="marker">Current semester</div>%s""" % ocenk
					sdivz += ocenk
					if len(result[s]) == 0:
						page += "No grades"
					page += "</code></div></button>"
					sdivz += "</div>"
					if len(savgarr) > 0:
						tempavg = round(sum(savgarr) / len(savgarr), 2)
						if not koniec:
							avgdict[s] = round(tempavg)
						if not check_tier(data["username"], "plus"):
							tempavg = '<div class="tier plus"></div>'
						else:
							tempavg = "%.02f" % tempavg
						savg = "<code> | </code>Average: %s" % tempavg
					divz += """<div id="%s" class="hidden" style="display: none"><button class="back" onclick="showdiv('%s', 'main', true)"></button><br><div class="header">%s</div><div class="subheader grades">Grades: %s%s</div>%s""" % (s.lower(), s.lower(), s, len(result[s]), savg, sdivz)
				score = 0
				if len(avgdict) >= 3:
					try:
						avg = "%.02f" % round(sum(avgdict.values()) / len(avgdict), 2)
						for x in avgdict:
							score += avgdict[x]
							fajnal = valueGrade(str(avgdict[x]))
							averages += """<div class="item"><div class="name">%s</div><div class="value"><i>%s</i><input class="fgrade" oninput="calc(this)" value="%s"></div></div>""" % (x, fajnal, fajnal)
					except:
						avg = "Broken"
				else:
					avg = "Unavailable"
				allocens.sort(key = lambda h: h["AddDate"], reverse = True)
				allocens = allocens[:50]
				for ocen in allocens:
					rcnt += ocen["string"]
				subjects = len(avgdict)
				allocens = len(allocens)
				hideavg = ""
				hidercnt =""
				REQ_TIER = "plus"
				if not check_tier(data["username"], REQ_TIER):
					rcnt = "<br>" + tierror(REQ_TIER, False, False, False)
					allocens = "N/A"
					grades = "Available in <div class=\"tier %s\"></div>" % REQ_TIER
					hidercnt = "display:none"
				REQ_TIER = "pro"
				if not check_tier(data["username"], REQ_TIER):
					averages = "<br>" + tierror(REQ_TIER, False, False, False)
					score = avg = "Available in <div class=\"tier %s\"></div>" % REQ_TIER
					subjects = "N/A"
					hideavg = "display:none"
				return response(resources["grades"] % (avg, grades, page, hideavg, avg, subjects, score, averages, hidercnt, allocens, rcnt, divz), 200)
			return response(resources["error"] % ("", "Error", ERR_403, mktryagainbtn("/grades", 1)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % ("", "Internal server error", tr, mktryagainbtn("/grades", 1)), 500)


async def more(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			tierr = ""
			if config["enable_tiers"] and database[data["username"]]["tier"] != "pro":
				tierr = """<button style="background: #FB0; color: #222" onclick="goto('settings', 3)">Upgrade your tier to access more cool features!</button><br>"""
			return response(resources["more"] % (tierr), 200)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % ("", "Internal server error", tr, mktryagainbtn("/more", 2)), 500)


async def settings(request):
	try:
		data = await request.json()
		if auth(data, request):
			timgs = glob(str(BASE_DIR / "static/img/profile/*"))
			imgs = ""
			if database[data["username"]]["custom_pic"]:
				imgs += """<button onclick="setProfilePic('%s')"><img onload="loadimg(this)" src="img/profile/custom/%s"></button>""" % (database[data["username"]]["custom_pic"], database[data["username"]]["custom_pic"])
			for img in timgs:
				img = os.path.basename(img)
				if img.endswith(".png"):
					imgs += """<button onclick="setProfilePic('%s')"><img onload="loadimg(this)" src="img/profile/%s"></button>""" % (img, img)
			f = ""
			if database[data["username"]]["profile_pic"] == database[data["username"]]["custom_pic"]:
				f = "custom/"
			grades_cleanup = ""
			if database[data["username"]]["grades_cleanup"]:
				grades_cleanup = "ed"
			atends_cleanup = ""
			if database[data["username"]]["attendances_cleanup"]:
				atends_cleanup = "ed"
			confeti = ""
			if database[data["username"]]["confetti"]:
				confeti = "ed"
			showupgrade = ""
			if database[data["username"]]["tier"] == "pro":
				showupgrade = "display:none"
			tiers = (linkify(config["tiers_requirements"]["free"]), linkify(config["tiers_requirements"]["plus"]), linkify(config["tiers_requirements"]["pro"]), config["tiers_text"])
			abouts = resources["about"] % parseContact(config["contact_uri"])
			return response(resources["settings"] % (f + database[data["username"]]["profile_pic"], database[data["username"]]["first_name"], database[data["username"]]["last_name"], data["username"], showupgrade, resources["tiers"] % tiers, abouts, imgs, parseDumbs(database[data["username"]]["l_login"]), parseDumbs(decrypt(database[data["username"]]["l_passwd"])), confeti, grades_cleanup, atends_cleanup), 200)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % ("", "Internal server error", tr, mktryagainbtn("/settings", 3)), 500)


async def timetable(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				res = await librus.get_timetable()
				week = "This week"
				if res["nextWeek"]:
					week = "Next week"
				result = res["Timetable"]
				page = ""
				details = ""
				lessons = 0
				for day in result:
					nc = [h for h in result[day] if not h["isCancelled"]]
					changes = "koolredb"
					lessons += len(result[day])
					details += """<div id="%s" class="hidden" style="display:none"><button class="back" onclick="showdiv('%s', 'overview', true)"></button><div class="header">%s</div><div class="subheader">%s lessons</div>""" % (day.lower(), day.lower(), day, len(result[day]))
					for x in result[day]:
						teacher = "%s %s" % (x["Teacher"]["FirstName"], x["Teacher"]["LastName"])
						change = ""
						changediv = "koolredb"
						if x["isSubstitution"]:
							change = " (Substitution)"
							changediv = "highlighted"
							changes = "highlighted"
						elif x["isCancelled"]:
							lessons -= 1
							teacher = "nobody"
							change = " (Cancelled)"
							changediv = "highlighted"
							changes = "highlighted"
						if x["Classroom"] != "unknown":
							details += """<button class="bubble unclickable wide %s"><div class="name">%s</div><div class="value">with %s</div><div class="value">Classroom %s</div><div class="value">%s - %s</div></button>""" % (changediv, x["Subject"] + change, teacher, x["Classroom"], x["HourFrom"], x["HourTo"])
						else:
							details += """<button class="bubble unclickable wide %s"><div class="name">%s</div><div class="value">with %s</div><div class="value">%s - %s</div></button>""" % (changediv, x["Subject"] + change, teacher, x["HourFrom"], x["HourTo"])
					hours = len(nc)
					page += """<button class="bubble %s" onclick="showdiv('overview', '%s')"><div class="name">%s</div><div class="value">%s lesson%s</div><div class="value">%s - %s</div></button>""" % (changes, day.lower(), day, hours, "s" if (hours != 1) else "", nc[0]["HourFrom"], nc[hours - 1]["HourTo"])
					details += "</div>"
				return response(resources["timetable"] % (week, lessons, page, details), 200)
			return response(resources["error"] % (mkbackbtn("/more", 2), "Error", ERR_403, mktryagainbtn("/timetable", 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn("/more", 2), "Internal server error", tr, mktryagainbtn("/timetable", 2)), 500)


async def attendances_old(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				result = (await librus.get_attendances())[::-1]
				page = ""
				lessons = 0
				presences = 0
				absences = 0
				for x in result:
					lessons += 1
					if x["isPresence"]:
						presences += 1
						color = "green"
					else:
						absences += 1
						if x["Short"] == "u":
							color = "yellow"
						else:
							color = "red"
					page += """<button class="bubble unclickable %s"><div class="name">%s</div><div class="value"><i>%s</i>, %s</div><div class="value">Added by %s %s</div><div class="value">%s</div></button>""" % (color, x["Lesson"], x["Type"], x["Date"], x["AddedBy"]["FirstName"], x["AddedBy"]["LastName"], x["Added"])
				wasted = "%sh %sm" % (math.floor(presences * 45 / 60), (presences * 45 % 60))
				try:
					pp = round((presences / lessons) * 100, 1)
				except:
					pp = "N/A "
				op = ""
				tier = ""
				buttons = ""
				REQ_TIER = "plus"
				PLUS_TIER = check_tier(data["username"], REQ_TIER)
				categs = ["Presences", "Excused absences", "Unexcused absences"]
				colors = ["green", "yellow", "red"]
				for x in colors:
					onclick = ""
					if PLUS_TIER:
						onclick = 'onclick="hideattendances(this, \'' + x + '\')"'
					clickable = "" if PLUS_TIER else "unclickable"
					buttons += """<div %s class="checked %s">%s</div>""" % (onclick, clickable, categs[colors.index(x)])
				if not PLUS_TIER:
					op = "opacity: .4"
					tier = REQ_TIER
				return response(resources["attendancesold"] % (pp, "%", presences, absences, lessons, wasted, tier, op, buttons, page), 200)
			return response(resources["error"] % (mkbackbtn("/more", 2), "Error", ERR_403, mktryagainbtn("/attendancesold", 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn("/attendances", 2), "Internal server error", tr, mktryagainbtn("/attendancesold", 2)), 500)


async def attendances(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			REQ_TIER = "pro"
			if not check_tier(data["username"], REQ_TIER):
				return tierror_resp(REQ_TIER, "/more", "Go to old Attendances", "attendancesold")
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				result = (await librus.get_attendances())[::-1]
				semesterEnds = (await librus.get_data("Classes"))["Class"]["EndFirstSemester"]
				semesterEnds = datetime.strptime(semesterEnds, '%Y-%m-%d')
				semesterEnds += timedelta(days = 1)
				persub = {}
				presences_total = 0
				absences_total = 0
				unexcused_total = 0
				for x in result:
					now = datetime.strptime(x["Date"], '%Y-%m-%d')
					semestr = semesterEnds <= now
					if x["Lesson"] not in persub:
						persub[x["Lesson"]] = {
							"presences": [0, 0],
							"absences": [0, 0],
							"unexcused": [0, 0],
							"html": ["", ""]
						}

					if x["isPresence"]:
						presences_total += 1
						persub[x["Lesson"]]["presences"][semestr] += 1
						color = "green"
					else:
						persub[x["Lesson"]]["absences"][semestr] += 1
						absences_total += 1
						if x["Short"] == "u":
							color = "yellow"
						else:
							unexcused_total += 1
							persub[x["Lesson"]]["unexcused"][semestr] += 1
							color = "red"
					e = """<button class="bubble unclickable %s"><div class="name">%s</div><div class="value"><i>%s</i>, %s</div><div class="value">Added by %s %s</div><div class="value">%s</div></button>""" % (color, x["Lesson"], x["Type"], x["Date"], x["AddedBy"]["FirstName"], x["AddedBy"]["LastName"], x["Added"])
					persub[x["Lesson"]]["html"][semestr] += e

				subjectos = ""
				html = ""
				for sub in persub:
					presences = sum(persub[sub]["presences"])
					absences = sum(persub[sub]["absences"])
					ful = presences + absences
					pp = math.floor(100 * presences / ful)
					if pp == 0 and database[data["username"]]["attendances_cleanup"]:
						continue
					wasted = "%sh %sm" % (math.floor(presences * 45 / 60), (presences * 45 % 60))
					barclass = ""
					auto_switch2 = (persub[sub]["presences"][1] + persub[sub]["absences"][1]) > 0
					hide1 = ""
					hide2 = ' class="hidden" style="display: none"'
					headchoice1 = ' class="selected"'
					headchoice2 = ''
					if auto_switch2:
						hide1 = ' class="hidden" style="display: none"'
						hide2 = ""
						headchoice1 = ""
						headchoice2 = ' class="selected"'
					if pp < 50:
						barclass = " danger"
					elif pp < 65:
						barclass = " warning"
					warntext = ""
					unxsds = sum(persub[sub]["unexcused"])
					if unxsds > 0:
						warntext = """<div class="value">%s unexcused</div>""" % (unxsds)
					subjectos += """<button class="bubble prog" onclick="showdiv('overview', '%s')"><div class="name">%s</div><div class="value">Frequency: %s%%</div>%s<div class="bar%s"><div style="width: %s%%"></div></div></button>""" % (sub, sub, pp, warntext, barclass, pp)
					progclass = ""
					if pp < 50:
						progclass = " danger"
					elif pp < 65:
						progclass = " warning"
					html += """<div id="%s" style="display: none;" class="hidden"><button class="back" onclick="showdiv('%s', 'overview', true)"></button><br><div class="header">%s</div><div class="subheader">Attendances</div><div class="progress%s"><div class="text"><div class="value"><b>%s</b></div><div class="text">%s out of %s<br>Wasted <b>%s</b></div></div><div class="bar"><div style="height: %s%%"></div></div></div>""" % (sub, sub, sub, progclass, pp, presences, ful, wasted, pp)
					html += """<div class="headchoice"><div%s onclick="headchoice('%s-2', '%s-1', this)">1st semester</div><div%s onclick="headchoice('%s-1', '%s-2', this)">2nd semester</div></div>""" % (headchoice1, sub, sub, headchoice2, sub, sub)
					sem_total = persub[sub]["presences"][0] + persub[sub]["absences"][0]
					if sem_total > 0:
						pp = math.floor(persub[sub]["presences"][0] / sem_total * 100)
					else:
						pp = "N/A"
					abwarn = ""
					if persub[sub]["unexcused"][0] > 0:
						abwarn = " danger"
					html += """<div id="%s-1"%s><button class="bubble highlighted unclickable"><div class="name">Presences</div><div class="value">%s%% | %s out of %s</div></button><button class="bubble highlighted unclickable%s"><div class="name">Absences</div><div class="value">%s, %s unexcused</div></button><br><br>%s</div>""" % (sub, hide1, pp, persub[sub]["presences"][0], sem_total, abwarn, persub[sub]["absences"][0], persub[sub]["unexcused"][0], persub[sub]["html"][0])
					sem_total = persub[sub]["presences"][1] + persub[sub]["absences"][1]
					if sem_total > 0:
						pp = math.floor(persub[sub]["presences"][1] / sem_total * 100)
					else:
						pp = "N/A"
					abwarn = ""
					if persub[sub]["unexcused"][1] > 0:
						abwarn = " danger"
					html += """<div id="%s-2"%s><button class="bubble highlighted unclickable"><div class="name">Presences</div><div class="value">%s%% | %s out of %s</div></button><button class="bubble highlighted unclickable%s"><div class="name">Absences</div><div class="value">%s, %s unexcused</div></button><br><br>%s</div>""" % (sub, hide2, pp, persub[sub]["presences"][1], sem_total, abwarn, persub[sub]["absences"][1], persub[sub]["unexcused"][1], persub[sub]["html"][1])
					html += "</div></div>"

				tots = absences_total + presences_total
				pp = math.floor(100 * presences_total / tots)
				wasted = "%sh %sm" % (math.floor(presences_total * 45 / 60), (presences_total * 45 % 60))
				progclass = ""
				if pp < 50:
					progclass = " danger"
				elif pp < 65:
					progclass = " warning"

				return response(resources["attendances"] % (progclass, pp, wasted, pp, presences_total, tots, absences_total, unexcused_total, subjectos, html), 200)
			return response(resources["error"] % (mkbackbtn("/more", 2), "Error", ERR_403, mktryagainbtn("/attendances", 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn("/more", 2), "Internal server error", tr, mktryagainbtn("/attendances", 2)), 500)


async def exams(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				result = (await librus.get_exams())[::-1]
				page = ""
				subject_closest = None
				now = datetime.now()
				date_closest = now + timedelta(90)
				for x in result:
					dat = datetime.strptime(x["Date"], '%Y-%m-%d')
					if dat < date_closest and now <= dat:
						date_closest = dat
						subject_closest = x["Lesson"]
					page += """<button class="bubble unclickable"><div class="name">%s</div><div class="value"><b>%s, %s - %s</b></div><div class="value">%s</div><div class="value"><i>%s</i></div><div class="value">Added by %s %s</div><div class="value">%s</div></button>""" % (x["Lesson"], x["Date"], x["StartTime"][:-3], x["EndTime"][:-3], x["Type"].title(), parseDumbs(x["Content"]), x["AddedBy"]["FirstName"], x["AddedBy"]["LastName"], x["AddDate"])
				nextin = "No exams so far"
				if not check_tier(data["username"], "plus"):
					nextin = 'Countdown available in <div class="tier plus"></div> tier.'
				elif subject_closest:
					first = "Next in"
					tm = date_closest
					daysto = (date_closest - now).days + 1
					if daysto == 0:
						first = "Today"
						tm = ""
					elif daysto == 1:
						first = "Tomorrow"
						tm = ""
					else:
						first = "Next in"
						tm = "%s days" % daysto
					nextin = "%s %s from %s" % (first, tm, subject_closest)
				return response(resources["exams"] % (len(result), nextin, page), 200)
			return response(resources["error"] % (mkbackbtn("/more", 2), "Error", ERR_403, mktryagainbtn("/exams", 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn("/more", 2), "Internal server error", tr, mktryagainbtn("/exams", 2)), 500)


async def freedays(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				res = await librus.get_free_days()
				result = sorted(res, key=lambda x: librus.parseDate(x["DateFrom"]), reverse=True)
				page = ""
				free_closest = None
				now = datetime.now()
				date_closest = now + timedelta(90)
				for x in result:
					dat = datetime.strptime(x["DateFrom"], '%Y-%m-%d')
					if dat < date_closest and now < dat:
						date_closest = dat
						free_closest = x["Name"]
					page += """<button class="bubble unclickable"><div class="name">%s</div><div class="value"><code>From &nbsp</code>%s</div><div class="value"><code>To &nbsp&nbsp&nbsp</code>%s</div></button>""" % (x["Name"], x["DateFrom"], x["DateTo"])
				nextin = "No free days in the future :("
				if not check_tier(data["username"], "plus"):
					nextin = 'Countdown available in <div class="tier plus"></div> tier.'
				elif free_closest:
					nice = ""
					daysto = (date_closest - now).days + 1
					if daysto == 1:
						nice = "starts tomorrow"
					else:
						nice = "in %s days" % daysto
					nextin = "%s %s" % (free_closest, nice)
				return response(resources["freedays"] % (len(result), nextin, page), 200)
			return response(resources["error"] % (mkbackbtn("/more", 2), "Error", ERR_403, mktryagainbtn("/freedays", 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn("/more", 2), "Internal server error", tr, mktryagainbtn("/freedays", 2)), 500)


async def teacherfreedays(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				result = (await librus.get_teacher_free_days())[::-1]
				page = ""
				result_sorted = sorted(result, key=lambda x: librus.parseDate(x["DateFrom"]), reverse=True)
				for x in result_sorted:
					teacher = "%s %s" % (x["Teacher"]["FirstName"], x["Teacher"]["LastName"])
					if "TimeTo" in x:
						page += """<button class="bubble unclickable"><div class="name">%s</div><div class="value"><code>From &nbsp</code>%s, %s</div><div class="value"><code>To &nbsp&nbsp&nbsp</code>%s, %s</div></button>""" % (teacher, x["DateFrom"], x["TimeFrom"][:-3], x["DateTo"], x["TimeTo"][:-3])
					else:
						page += """<button class="bubble unclickable"><div class="name">%s</div><div class="value"><code>From &nbsp</code>%s</div><div class="value"><code>To &nbsp&nbsp&nbsp</code>%s</div></button>""" % (teacher, x["DateFrom"], x["DateTo"])
				return response(resources["teacherfreedays"] % (len(result), page), 200)
			return response(resources["error"] % (mkbackbtn("/more", 2), "Error", ERR_403, mktryagainbtn("/teacherfreedays", 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn("/more", 2), "Internal server error", tr, mktryagainbtn("/teacherfreedays", 2)), 500)


async def parentteacherconferences(request):
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				result = (await librus.get_parent_teacher_conferences())[::-1]
				page = ""
				for x in result:
					page += """<button class="bubble unclickable wide"><div class="name">%s</div><div class="value"><i>%s</i></div><div class="value">Classroom %s</div><div class="value">%s at %s</div></button>""" % (x["Name"], x["Topic"], x["Room"], x["Date"], (x["Time"])[:-3])
				return response(resources["parentteacherconferences"] % (len(result), page), 200)
			return response(resources["error"] % (mkbackbtn("/more", 2), "Error", ERR_403, mktryagainbtn("/parentteacherconferences", 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn("/more", 2), "Internal server error", tr, mktryagainbtn("/parentteacherconferences", 2)), 500)


async def school(request):
	global database
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				result = await librus.get_school()
				me = await librus.get_me()
				student = "%s %s" % (database[data["username"]]["first_name"], database[data["username"]]["last_name"])
				student_new = "%s %s" % (me["FirstName"], me["LastName"])
				if student != student_new:
					database[data["username"]]["first_name"] = me["FirstName"]
					database[data["username"]]["last_name"] = me["LastName"]
				tutor = "%s %s" % (me["Tutor"]["FirstName"], me["Tutor"]["LastName"])
				address = "%s %s, %s %s" % (result["Street"], result["BuildingNumber"], result["PostCode"], result["Town"])
				sendz = datetime.strptime(me["SchoolYearMiddles"], '%Y-%m-%d').strftime("%d %B %Y")
				endz = datetime.strptime(me["SchoolYearEnds"], '%Y-%m-%d').strftime("%d %B %Y")
				return response(resources["school"] % (result["Name"], address, student, tutor, me["Class"], me["Type"], sendz, endz), 200)
			return response(resources["error"] % (mkbackbtn("/more", 2), "Error", ERR_403, mktryagainbtn("/school", 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn("/more", 2), "Internal server error", tr, mktryagainbtn("/school", 2)), 500)


async def messages(request):
	global database
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			REQ_TIER = "plus"
			if not check_tier(data["username"], REQ_TIER):
				return tierror_resp(REQ_TIER, "/more", False, False)
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				inbox = await librus.get_messages()
				html = ""
				for mesg in inbox:
					html += """<button class="bubble wide" onclick="goto('message/%s', 2, true)"><div class="name">%s</div><div class="value">from <b>%s</b></div><div class="value">%s</div></div>""" % (mesg["link"], mesg["subject"], mesg["from"], mesg["date"])
				return response(resources["messages"] % (len(inbox), html), 200)
			return response(resources["error"] % (mkbackbtn("messages", 2), "Error", ERR_403, mktryagainbtn("/messages", 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn("messages", 2), "Internal server error", tr, mktryagainbtn("/messages", 2)), 500)


async def traffic(request):
	return response(resources["traffic"], 200)

async def getTraffic(request):
	return JSONresponse(librus.REQUESTS, 200)


async def message(request):
	global database
	uri = request.match_info["uri"]
	uri_full = "message/%s" % uri
	try:
		data = await request.json()
		if auth(data, request):
			demo = demo_err(data["username"])
			if demo != False: return demo
			REQ_TIER = "pro"
			if not check_tier(data["username"], REQ_TIER):
				return tierror_resp(REQ_TIER, "/messages", False, False)
			librus = Librus(SESSIONS.get(data["username"]))
			if await librus.mktoken(database[data["username"]]["l_login"], decrypt(database[data["username"]]["l_passwd"])):
				SESSIONS.save(data["username"], librus.headers)
				mesg = await librus.get_message(uri)
				attachments = ""
				for file in mesg["attachments"]:
					attachments += """<div onclick="downloadMsgFile(this, '%s')">%s</div>""" % (file["nice"].replace("/", "-"), parseDumbs(file["name"]))
				return response(resources["message"] % (mesg["subject"], mesg["from"], mesg["date"], mesg["content"], attachments, mesg["read"]), 200)
			return response(resources["error"] % (mkbackbtn(uri_full, 2), "Error", ERR_403, mktryagainbtn(uri_full, 2)), 403)
		return response("", 401)
	except:
		tr = copyable_tr(traceback.format_exc().replace(LIBRUSIK_PATH, ""))
		return response(resources["error"] % (mkbackbtn(uri_full, 2), "Internal server error", tr, mktryagainbtn(uri_full, 2)), 500)


async def message_download_file(request):
	global database
	uri = request.match_info["uri"]
	uri = uri.replace("-", "/")
	try:
		username = AUTH_SESSIONS.get_subject(request.cookies.get(USER_SESSION_COOKIE))
		if username in database:
			demo = demo_err(username)
			if demo != False: return demo
			if not check_tier(username, "pro"):
				return response("", 700)
			librus = Librus(SESSIONS.get(username))
			if await librus.mktoken(database[username]["l_login"], decrypt(database[username]["l_passwd"])):
				SESSIONS.save(username, librus.headers)
				file = await librus.download_file(uri)
				if not file:
					return response("Couldn't download the attachment.", 502)
				content_type = file["headers"].get("Content-Type", "application/octet-stream")
				resp = web.Response(body=file["content"], status=200, content_type=content_type)
				if file["headers"].get("Content-Disposition"):
					resp.headers["Content-Disposition"] = file["headers"]["Content-Disposition"]
				return resp
		return response("", 401)
	except Exception:
		traceback.print_exc()
		return response("Internal server error.", 500)


####################################################################################################################################################################################################

async def panelapi(request):
	try:
		data = await request.json()
		if not isinstance(data, dict) or "method" not in data:
			return response("", 400)
		if data["method"] == "auth":
			name = data.get("name")
			password = data.get("password")
			if name == config["name"] and verify_password(password, config["passwd"]):
				if needs_rehash(config["passwd"]):
					config["passwd"] = hash_password(password)
					updateconf()
				token = PANEL_SESSIONS.create("admin")
				resp = response("", 200)
				set_session_cookie(resp, PANEL_SESSION_COOKIE, token, request)
				return resp
			return response("", 401)
		if not panel_auth(request):
			return response("", 401)
		if data["method"] == "get_data":
			uptime = round(time.time()) - BOOT
			users = []
			for username, user in database.items():
				last_seen = LAST_SEEN_PEPS.get(username, -1)
				pic = user["profile_pic"]
				if user["profile_pic"] == user["custom_pic"]:
					pic = f"custom/{pic}"
				users.append({
					"first_name": user["first_name"],
					"last_name": user["last_name"],
					"username": username,
					"pic": pic,
					"last_seen": last_seen,
					"joined": user["joined"],
					"tier": user["tier"],
					"demotier": demoleft(username)
				})
			maxusers = config["max_users"]
			db_usage = round(len(users) / maxusers * 100)
			db_size = round(os.stat(STORE.path).st_size / 10) / 100
			return JSONresponse({
				"users": users[::-1],
				"max_users": maxusers,
				"db_usage": db_usage,
				"uptime": uptime,
				"db_size": db_size,
				"host": host
			}, 200)
		if data["method"] == "passwd":
			if not isinstance(data.get("newpass"), str) or not checklen(data["newpass"], 4, 32):
				return response("", 400)
			if not verify_password(data.get("current_password"), config["passwd"]):
				return response("", 401)
			config["passwd"] = hash_password(data["newpass"])
			updateconf()
			return response("", 200)
		if data["method"] == "name":
			if not isinstance(data.get("newname"), str) or not checklen(data["newname"], 4, 16):
				return response("", 400)
			if not verify_password(data.get("current_password"), config["passwd"]):
				return response("", 401)
			if not re.fullmatch("[a-z0-9]+", data["newname"]):
				return response("", 400)
			config["name"] = data["newname"]
			updateconf()
			return response("", 200)
		if data["method"] == "reboot":
			raise SystemExit
		if data["method"] == "chgmaxusers":
			if isinstance(data.get("maxusers"), int) and 1 <= data["maxusers"] <= 128:
				config["max_users"] = data["maxusers"]
				updateconf()
				return response("", 200)
		if data["method"] == "deluser":
			username = data.get("username")
			if not isinstance(username, str):
				return response("", 400)
			if username not in database:
				return response("", 403)
			custom_pic = database[username]["custom_pic"]
			if custom_pic:
				pic_path = os.path.join(PROFILE_PIC_DIR, custom_pic)
				if os.path.isfile(pic_path):
					os.remove(pic_path)
			del database[username]
			updatedb()
			return response("", 200)
		if data["method"] == "changetier":
			username = data.get("username")
			if not isinstance(username, str) or data.get("tier") not in TIERS:
				return response("", 400)
			if username not in database:
				return response("", 403)
			database[username]["tier"] = data["tier"]
			updatedb()
			return response("", 200)
		if data["method"] == "genuserpass":
			username = data.get("username")
			if not isinstance(username, str):
				return response("", 400)
			if username not in database:
				return response("", 403)
			newpassw = randompasswd()
			database[username]["passwd"] = hash_password(newpassw)
			updatedb()
			return response(newpassw, 200)
		if data["method"] == "getconf":
			public_config = dict(config)
			public_config.pop("passwd", None)
			return JSONresponse(public_config, 200)
		if data["method"] == "setnotice":
			if isinstance(data.get("notice"), str):
				config["notice"] = data["notice"] or None
				updateconf()
				return response("", 200)
		if data["method"] == "setcontact":
			if isinstance(data.get("contact_uri"), str):
				config["contact_uri"] = data["contact_uri"]
				updateconf()
				return response("", 200)
		if data["method"] == "settiers":
			if (isinstance(data.get("enable_tiers"), bool)
					and isinstance(data.get("tiers_text"), str)
					and isinstance(data.get("tiers_requirements"), dict)):
				config["enable_tiers"] = data["enable_tiers"]
				config["tiers_text"] = data["tiers_text"]
				config["tiers_requirements"] = data["tiers_requirements"]
				updateconf()
				return response("", 200)
		if data["method"] == "setregistration":
			if isinstance(data.get("enabled"), bool):
				config["enable_registration"] = data["enabled"]
				updateconf()
				return response("", 200)
		return response("", 400)
	except SystemExit:
		raise SystemExit
	except Exception:
		traceback.print_exc()
		return response("Internal server error.", 500)


async def panel(request):
	hidetiers = "" if config["enable_tiers"] else ".tiers{display:none !important}"
	return response(resources["panel"] % (config["subdirectory"], hidetiers), 200)

async def panell(request):
	return response(resources["panellogin"] % (config["subdirectory"]), 200)


async def upload_handler(request):
	try:
		data = await request.post()
		if not auth(data, request):
			return web.Response(text = json.dumps({"ok": False}), status = 401, headers = {'Content-Type': 'application/json'})
		username = session_username(request)
		if username not in database:
			return response("File is way too big!", 401)
		img = data.get("file")
		if img is None or not hasattr(img, "file"):
			return response("File is invalid!", 400)
		content_type = img.headers.get("Content-Type", "")
		content = img.file.read()
		if len(content) > 4 * 1024 * 1024:
			return response("File is way too big!", 413)
		is_png = content_type == "image/png" and content.startswith(b"\x89PNG\r\n\x1a\n")
		is_jpeg = content_type == "image/jpeg" and content.startswith(b"\xff\xd8\xff")
		if not (is_png or is_jpeg):
			return response("Attached file is not a photo.", 400)
		filename = f"{uuid.uuid4().hex}.{'png' if is_png else 'jpg'}"
		if not os.path.exists(PROFILE_PIC_DIR):
			os.mkdir(PROFILE_PIC_DIR)
		with open(os.path.join(PROFILE_PIC_DIR, filename), "wb") as f:
			f.write(content)
		if database[username]["custom_pic"]:
			oldpic = "%s/%s" % (PROFILE_PIC_DIR, database[username]["custom_pic"])
			if os.path.isfile(oldpic):
				os.remove(oldpic)
		database[username]["custom_pic"] = filename
		database[username]["profile_pic"] = filename
		updatedb()
		return response("", 200)
	except:
		return response("File is invalid!", 500)


async def set_profile_pic(request):
	try:
		data = await request.json()
		if auth(data, request):
			picture = data.get("picture")
			available = {os.path.basename(path) for path in glob(str(BASE_DIR / "static/img/profile/*.png"))}
			custom = database[data["username"]]["custom_pic"]
			if picture != custom and picture not in available:
				return response("Invalid profile picture.", 400)
			database[data["username"]]["profile_pic"] = picture
			updatedb()
			return response("", 200)
		return response("", 401)
	except Exception:
		traceback.print_exc()
		return response("Internal server error.", 500)


@web.middleware
async def error_middleware(request, handler):
	try:
		useragent = request.headers["User-Agent"]
		passed = False
		if config["check_browser"]:
			for check in ["Chrome", "Safari", "Firefox"]:
				if check in useragent: passed = True
		else: passed = True
		if not passed: raise Exception("VerificationError")
		respons = await handler(request)
		respons.headers["Cache-Control"] = "no-cache"
		return respons
	except Exception as ex:
		traceback.print_exc()
		exc = "Request failed."
		try:
			status = ex.status
		except AttributeError:
			status = 400
		if status == 400:
			exc = "Bad Request"
		elif status == 401:
			exc = "Unauthorized: You are not authorized to view this resource."
		elif status == 403:
			exc = "Forbidden: You are not permitted to view this resource."
		elif status == 404:
			exc = "Not Found: The requested resource does not exist."
		return response(resources["errorpage"] % (str(status), str(exc)), status)


middlewares = []
if not config["debug"]:
	middlewares.append(error_middleware)

app = web.Application(middlewares = middlewares, client_max_size = 1024**2*4)
app.logger.manager.disable = 100 - 100 * config["debug"]


app.add_routes([
	web.route('GET', '/', index),
	web.route('GET', '/login', login),
	web.route('POST', '/auth', authenticate),
	web.route('POST', '/session', session_status),
	web.route('POST', '/logout', logout),
	web.route('POST', '/api', api),
	web.route('POST', '/home', home),
	web.route('POST', '/grades', grades),
	web.route('POST', '/more', more),
	web.route('POST', '/settings', settings),
	web.route('POST', '/timetable', timetable),
	web.route('POST', '/attendances', attendances),
	web.route('POST', '/attendancesold', attendances_old),
	web.route('POST', '/exams', exams),
	web.route('POST', '/freedays', freedays),
	web.route('POST', '/teacherfreedays', teacherfreedays),
	web.route('POST', '/parentteacherconferences', parentteacherconferences),
	web.route('POST', '/school', school),
	web.route('POST', '/traffic', traffic),
	web.route('GET', '/getTraffic', getTraffic),
	web.route('POST', '/messages', messages),
	web.route('POST', '/message/{uri}', message),
	web.route('GET', '/message_download_file/{uri}', message_download_file),
	web.route('GET', '/panel', panel),
	web.route('GET', '/panel/login', panell),
	web.route('POST', '/panel/session', panel_session_status),
	web.route('POST', '/panel/logout', panel_logout),
	web.route('POST', '/panel/api', panelapi),
	web.route("POST", "/api/uploadProfilePic", upload_handler),
	web.route("POST", "/api/setProfilePic", set_profile_pic),
	web.route("POST", "/api/forgotPassword", forgot_pass),
	web.static('/img/profile/custom', PROFILE_PIC_DIR),
	web.static('/', str(BASE_DIR / 'static'))
])

async def run_server(open_browser=False, on_started=None, shutdown_event=None, port=None):
	"""Run LibreCap until the host application asks it to stop."""
	global ACTIVE_PORT
	runner = web.AppRunner(app)
	await runner.setup()
	ssl_context = None
	if config["ssl"]:
		ssl_context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
		ssl_context.load_cert_chain(config["pubkey"], config["privkey"])
	site = web.TCPSite(
		runner,
		host=config["listen_address"],
		port=config["port"] if port is None else port,
		ssl_context=ssl_context,
	)
	await site.start()
	sockets = site._server.sockets if site._server else []
	ACTIVE_PORT = sockets[0].getsockname()[1] if sockets else config["port"]
	title_task = asyncio.create_task(updatetitles())
	try:
		if on_started:
			on_started()
		if open_browser:
			import webbrowser
			webbrowser.open(local_url())
		if shutdown_event is None:
			await asyncio.Event().wait()
		else:
			await shutdown_event.wait()
	finally:
		ACTIVE_PORT = None
		title_task.cancel()
		await runner.cleanup()


async def main():
	await run_server()


if __name__ == "__main__":
	asyncio.run(main())
