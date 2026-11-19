"""Development helper for an explicitly authorized Android test phone; never part of the APK."""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import sys
sys.stdout.reconfigure(encoding="utf-8",errors="replace")

parser=argparse.ArgumentParser()
parser.add_argument("--serial",required=True)
parser.add_argument("action",choices=("snapshot","tap-text","tap-description","screenshot"))
parser.add_argument("value",nargs="?",default="")
parser.add_argument("--nth",type=int,default=0)
parser.add_argument("--top",type=int,default=10000)
parser.add_argument("--bottom",type=int,default=0)
args=parser.parse_args()
def adb(*parts):
    return subprocess.run(["adb","-s",args.serial,*parts],check=True,capture_output=True).stdout
if args.action=="screenshot":
    # uiautomator dump temporarily suppresses other AccessibilityServices. Never attach it
    # while capturing a local agent action; check the focused window without UiAutomation.
    windows=adb("shell","dumpsys","window").decode("utf-8",errors="replace")
    if not re.search(r"mCurrentFocus=Window\{[^\r\n]*\borg\.campana\.agent/",windows):
        raise SystemExit("Capture limited to a focused Campaña window")
    path=Path(args.value);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(adb("exec-out","screencap","-p"));print(path)
    raise SystemExit(0)
# Snapshot/tap discovery may briefly disconnect services; do not run it during an agent action.
adb("shell","uiautomator","dump","/sdcard/campana-qa.xml")
raw=adb("shell","cat","/sdcard/campana-qa.xml")
root=ET.fromstring(raw)
nodes=list(root.iter("node"))
def center(node):
    numbers=list(map(int,re.findall(r"-?\d+",node.attrib["bounds"])))
    return ((numbers[0]+numbers[2])//2,(numbers[1]+numbers[3])//2)
if args.action=="snapshot":
    for node in nodes:
        a=node.attrib
        bounds=list(map(int,re.findall(r"-?\d+",a.get("bounds",""))))
        if bounds and bounds[1]>args.top:continue
        if bounds and bounds[1]<args.bottom:continue
        if a.get("password")=="true": continue
        if a.get("text") or a.get("content-desc"):
            text=a.get("text","")
            if "?" in text and ("auth/callback" in text or "token=" in text or "code=" in text):text=text.split("?")[0]+"?[redacted]"
            print(a.get("class","").split(".")[-1],repr(text[:130]),repr(a.get("content-desc","")[:100]),a.get("resource-id",""),a.get("bounds"))
elif args.action.startswith("tap"):
    key="text" if args.action=="tap-text" else "content-desc"
    candidates=[n for n in nodes if n.attrib.get(key)==args.value]
    if args.nth>=len(candidates): raise SystemExit("No matching visible element")
    x,y=center(candidates[args.nth]);adb("shell","input","tap",str(x),str(y))
    print("Tapped the matching element")
