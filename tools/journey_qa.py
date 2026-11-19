"""Human-side Android QA commands. Requires the explicitly opted-in instrumentation bridge.

Not part of the application or its runtime. UI input is restricted to Campaña; social
sends/publications must be performed by the on-phone agent, never by this helper.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
p=argparse.ArgumentParser()
p.add_argument("--serial",required=True)
p.add_argument("action",choices=["dump","tap","write","screenshot","assets","export-image","finish"])
p.add_argument("value",nargs="?",default="")
p.add_argument("--package",default="org.campana.agent",choices=["org.campana.agent","com.whatsapp","com.facebook.katana"])
p.add_argument("--text-file")
p.add_argument("--index",type=int)
p.add_argument("--top",type=int,default=10000)
a=p.parse_args()
adb=Path(os.environ["ANDROID_HOME"])/"platform-tools/adb.exe"
remote="/sdcard/Android/data/org.campana.agent/files/conversation-journey"
request={"id":str(uuid.uuid4()),"action":a.action,"package":a.package}
if a.action=="write":request["text"]=Path(a.text_file).read_text(encoding="utf-8") if a.text_file else a.value
if a.action=="tap":request["label"]=a.value
if a.index is not None:request["index"]=a.index
if a.action=="screenshot":request["filename"]=a.value
if a.action=="export-image":request["name"]=a.value
folder=Path(__file__).resolve().parent.parent/"app/build/full-journey"
folder.mkdir(parents=True,exist_ok=True)
local=folder/(request["id"]+".json")
local.write_text(json.dumps(request,ensure_ascii=False),encoding="utf-8")
def run(*parts,check=True):
    return subprocess.run([str(adb),"-s",a.serial,*parts],check=check,capture_output=True)
run("push",str(local),remote+"/command.json")
for _ in range(100):
    read=run("shell","cat",remote+"/response.json",check=False)
    try:result=json.loads(read.stdout)
    except (ValueError,UnicodeError):result={}
    if result.get("id")==request["id"]:break
    time.sleep(.1)
else:raise SystemExit("QA bridge did not respond. No action was retried automatically.")
if "nodes" in result:
    for n in result.pop("nodes"):
        bounds=list(map(int,re.findall(r"-?\d+",n.get("bounds",""))))
        if bounds and bounds[1]>a.top:continue
        print(json.dumps(n,ensure_ascii=False))
print(json.dumps(result,ensure_ascii=False))
if not result.get("ok"):raise SystemExit(1)
if a.action=="tap":time.sleep(.4)  # Let native dialogs finish their focus transition.
