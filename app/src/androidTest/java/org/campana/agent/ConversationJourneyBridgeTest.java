package org.campana.agent;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Opt-in human-side QA driver. No database/agent calls and no social send or publish actions. */
public class ConversationJourneyBridgeTest {
    private static final String OWN = "org.campana.agent";
    private final java.util.ArrayList<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();

    @Test public void humanConversationBridge() throws Exception {
        Bundle args=InstrumentationRegistry.getArguments();
        Assume.assumeTrue("true".equals(args.getString("enableConversationJourney")));
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=new File(context.getExternalFilesDir(null),"conversation-journey");
        if(!dir.exists() && !dir.mkdirs())throw new IllegalStateException("QA directory unavailable");
        UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        write(new File(dir,"ready.json"),new JSONObject().put("ready",true).put("package",context.getPackageName()));
        String previous="";
        long deadline=SystemClock.elapsedRealtime()+35*60_000L;
        File command=new File(dir,"command.json");
        while(SystemClock.elapsedRealtime()<deadline) {
            if(command.isFile()) {
                JSONObject c;
                try {c=new JSONObject(new String(Files.readAllBytes(command.toPath()),StandardCharsets.UTF_8));}
                catch(Exception incomplete){SystemClock.sleep(100);continue;}
                String id=c.optString("id");
                if(!id.isEmpty() && !id.equals(previous)) {
                    previous=id;
                    JSONObject result=new JSONObject().put("id",id);
                    try {
                        String action=c.getString("action");
                        if("finish".equals(action)){result.put("ok",true);write(new File(dir,"response.json"),result);return;}
                        AccessibilityNodeInfo root=automation.getRootInActiveWindow();
                        if(root==null)throw new IllegalStateException("No active window");
                        String pkg=String.valueOf(root.getPackageName());
                        String expected=c.getString("package");
                        if(!java.util.Arrays.asList(OWN,"com.whatsapp","com.facebook.katana").contains(expected) || !expected.equals(pkg))
                            throw new IllegalStateException("Unexpected focused app: "+pkg);
                        nodes.clear();visit(root,0);
                        if("dump".equals(action)) {
                            JSONArray rows=new JSONArray();
                            for(int i=0;i<nodes.size();i++) {
                                AccessibilityNodeInfo n=nodes.get(i);Rect bounds=new Rect();n.getBoundsInScreen(bounds);
                                if(n.isPassword() || !n.isVisibleToUser())continue;
                                String text=n.getText()==null?"":n.getText().toString();
                                String description=n.getContentDescription()==null?"":n.getContentDescription().toString();
                                if(text.isEmpty() && description.isEmpty() && !n.isEditable() && !n.isClickable())continue;
                                rows.put(new JSONObject().put("index",i).put("text",text).put("description",description)
                                    .put("resource",n.getViewIdResourceName()).put("editable",n.isEditable()).put("clickable",n.isClickable())
                                    .put("enabled",n.isEnabled()).put("hint",n.getHintText()).put("showingHint",n.isShowingHintText()).put("bounds",bounds.toShortString()));
                            }
                            result.put("nodes",rows);
                        } else if("screenshot".equals(action)) {
                            String filename=c.getString("filename");
                            if(!filename.matches("[a-z0-9-]+\\.png"))throw new IllegalArgumentException("Invalid QA filename");
                            Bitmap bitmap=automation.takeScreenshot();if(bitmap==null)throw new IllegalStateException("No screenshot");
                            try(FileOutputStream out=new FileOutputStream(new File(dir,filename))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
                            result.put("file",filename);
                        } else {
                            if(!OWN.equals(pkg))throw new IllegalStateException("Human QA input is limited to Campaña. Social actions belong to the phone agent.");
                            if("assets".equals(action)) {
                                JSONArray files=new JSONArray();File folder=new File(context.getFilesDir(),"assets");
                                File[] found=folder.listFiles();if(found!=null)for(File f:found)if(f.isFile() && f.getName().endsWith(".png"))files.put(new JSONObject().put("name",f.getName()).put("modified",f.lastModified()).put("bytes",f.length()));
                                result.put("assets",files);
                            } else if("export-image".equals(action)) {
                                String name=c.getString("name");if(!name.matches("[a-fA-F0-9-]{36}\\.png"))throw new IllegalArgumentException("Invalid asset filename");
                                File source=new File(new File(context.getFilesDir(),"assets"),name);
                                if(!source.isFile() || source.length()>10_000_000)throw new IllegalArgumentException("Image unavailable");
                                Files.copy(source.toPath(),new File(dir,"poster.png").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                result.put("file","poster.png");
                            } else if("write".equals(action)) {
                                java.util.ArrayList<AccessibilityNodeInfo> inputs=new java.util.ArrayList<>();
                                for(AccessibilityNodeInfo n:nodes)if(n.isEditable() && n.isVisibleToUser() && !n.isPassword())inputs.add(n);
                                if(inputs.size()!=1)throw new IllegalStateException("Expected the single chat composer");
                                String text=c.getString("text");if(text.length()>12000)throw new IllegalArgumentException("Text too long");
                                Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
                                if(!inputs.get(0).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value))throw new IllegalStateException("Chat input not accepted");
                            } else if("tap".equals(action)) {
                                String label=c.getString("label");java.util.ArrayList<AccessibilityNodeInfo> matches=new java.util.ArrayList<>();
                                for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser() && !n.isPassword() && (label.contentEquals(n.getText()==null?"":n.getText()) || label.contentEquals(n.getContentDescription()==null?"":n.getContentDescription())))matches.add(n);
                                int which=c.optInt("index",0);
                                if(matches.size()!=1 && !c.has("index"))throw new IllegalStateException("Expected one matching control, got "+matches.size());
                                AccessibilityNodeInfo target=matches.get(which);
                                for(int depth=0;!target.isClickable() && depth<4 && target.getParent()!=null;depth++)target=target.getParent();
                                if(!target.isEnabled() || !target.isClickable() || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new IllegalStateException("Control not available");
                            } else throw new IllegalArgumentException("Unknown human QA action");
                        }
                        result.put("ok",true).put("package",pkg);
                    } catch(Exception error) {result.put("ok",false).put("error",error.getMessage());}
                    write(new File(dir,"response.json"),result);
                }
            }
            SystemClock.sleep(100);
        }
        throw new IllegalStateException("Human QA bridge time limit reached");
    }
    private void visit(AccessibilityNodeInfo n,int depth) {
        if(depth>28 || nodes.size()>1500)return;
        nodes.add(n);for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null)visit(child,depth+1);}
    }
    private void write(File file,JSONObject object) throws Exception {
        File temp=new File(file.getParentFile(),file.getName()+".tmp");
        Files.write(temp.toPath(),object.toString().getBytes(StandardCharsets.UTF_8));
        if(!temp.renameTo(file))throw new IllegalStateException("Cannot write QA response");
    }
}
