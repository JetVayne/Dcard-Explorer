import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.net.URLEncoder;
import java.net.URLDecoder;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.io.*;
import java.util.ArrayList;
import org.jsoup.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Node;
import org.jsoup.Connection;
import org.jsoup.Connection.Response;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.jsoup.nodes.Element;
import org.jsoup.helper.HttpConnection;
import org.json.JSONArray;
import org.json.JSONObject;
import java.sql.Timestamp;
import java.net.URL;
import org.apache.log4j.*;
import org.apache.log4j.xml.DOMConfigurator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.sql.*;
import java.util.Properties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import groovy.transform.Field;

@Field Logger logger = Logger.getLogger("scu");
@Field String site = "{SITENAME}";
@Field String jobtxdate = "{LASTTXDATE}";
@Field int stop = 10;
@Field int outsider = 0;
@Field boolean netError = false;
@Field DateFormat jobtxDf = new SimpleDateFormat("yyyy-MM-dd");
@Field DateFormat postDf1 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
@Field List<String> postInfoList = new ArrayList<>();
@Field firstRuleString = "";
@Field endRuleString = "";
@Field replaceRuleString = "";
@Field String runUrl;
@Field String runStep;
@Field String board = "{BOARD}";

DOMConfigurator.configure("C:/log4j.xml");
try {
	
    getPost();
	
} catch (Exception e) {
    logger.info("error step: "+runStep);
    logger.error(getStackTrace(e));
}

def void getPost(){
    runStep = "[getPost]";
    boolean flag = false;
    String pageUrl = "https://www.dcard.tw/_api/forums/"+board+"/posts?popular=false&limit=100";

    while(!flag){
        runStep = "[getPost]";
        String pageJStr = getJString(pageUrl);
        if(pageJStr==null){
            return;
        }

        JSONArray pageJSON;
        try{
            pageJSON = new JSONArray(pageJStr);
        }catch(Exception e){
            logger.error("Failed to parse api source of post-page to JSONArray. url: "+pageUrl);
            return;
        }

        if(pageJSON.length()==0){
            return;
        }

        for(int i = 0; i < pageJSON.length(); i++){
            JSONObject post = pageJSON.opt(i);
            if(post.opt("hidden")==true){
                continue;
            }

            String artId = post.opt("id");
            int comCount = post.opt("commentCount");
            String title = post.opt("title");
            String pdStr = post.opt("createdAt");

            if(comCount == 0){
                if(checkTime(dateFormat(pdStr))){
                    getArticle(artId, title);
                }else{
                    outsider +=1;
					logger.info("find one outsider. count of outsider: "+outsider);
                }

            }else{
                getComment(artId, comCount, title);
                if(checkTime(dateFormat(pdStr))){
                    getArticle(artId, title);
                }
            }

            if(outsider==stop){
                flag = true;
                break;
            }

        }

        String lastPostId = pageJSON.opt(pageJSON.length()-1).opt("id");
        if(lastPostId != null){
            pageUrl = "https://www.dcard.tw/_api/forums/"+board+"/posts?popular=false&limit=100&before="+lastPostId;
        }else{
            return;
        }

    }
}

def void getComment(String artId, Integer comCount, String title){
    runStep = "[getComment]";
    boolean flag = false;
    String dcUrl = "https://www.dcard.tw/f/"+board+"/p/"+artId;
    List<String> updateList = new ArrayList<>();
    boolean updateFlag = true;

    int jumper = getJumper(comCount);

    for(int p = jumper; p >= 0; p-=100){
        String comPageUrl = "https://www.dcard.tw/_api/posts/"+artId+"/comments?limit=100&after="+p;
        String comPageJStr = getJString(comPageUrl);
        if(comPageJStr==null){
            return;
        }

        JSONArray comJSON;
        try{
            comJSON = new JSONArray(comPageJStr);
        }catch(Exception e){
            logger.error("Failed to parse api source of post-page to JSONArray. url: "+comPageUrl);
            continue;
        }

        if(comJSON.length()==0){
            continue;
        }

        for(int i = comJSON.length()-1; i >= 0 ; i--){
            JSONObject comment = comJSON.opt(i);
            if(comment.opt("hidden")==true){
                continue;
            }

            String cdStr = comment.opt("createdAt");

            if(updateFlag){
                if(cdStr!=null&&updateList.size()==0){
                    updateList.add(cdStr);
                    checkUpdate(updateList);
                    updateFlag = false;
                }
            }

            if(checkTime(dateFormat(cdStr))){
                String comId = artId+"_"+comment.opt("floor");
                String author = getAuthor(comment);
                String pid = getPid(artId, comment);
                String content = comment.opt("content");
                int likeCount = comment.opt("likeCount");
                Timestamp articleDate = new Timestamp(dateFormat(cdStr).getTime());

                HashMap output = save(comId, site, artId, pid, dcUrl, title, author, content, likeCount, articleDate);
				outputList.add(output);

            }else{
                flag = true;
                break;
            }


        }

        if(flag){
            break;
        }
    }
}

def void checkUpdate(List input){
    if(!checkTime(dateFormat(input[0]))){
        outsider +=1;
		logger.info("find one outsider. count of outsider: "+outsider);
    }
}

def String getPid(String artId, JSONObject comment){
    String result = "";
    String temp = comment.opt("content");
    Set<String> floorSet = new HashSet<>();
    Reader reader = new StringReader(temp);
    StringBuilder sb = new StringBuilder();
    String line = null;
    while ((line = reader.readLine()) != null){
        if(!isUrl(line)){
            floorSet.addAll(findFloor(line));
        }
    }

    reader.close();

    result = parseFloor(floorSet, artId);

    return result;
}

def String parseFloor(Set<String> floorSet, String artId){
    String result = "";
    if(floorSet.size()==0){
        return artId;    //***
    }else{
        String floor = floorSet.iterator()[0].replace("B","");
        
        if(floor.equals("0")){
            return artId;    //***
        }
        
        result = artId+"_"+floor
    }

    return result;
}


def Set findFloor(String input){
    Set<String> result = new HashSet<>();
    Pattern pattern = Pattern.compile("(B)\\d+ ");
    Matcher m = pattern.matcher(input);

    while (m.find()) {
        result.add(m.group(0).trim());
    }

    return result;
}

def Integer getJumper(Integer input){
    int result = 0;
    if(input % 100 == 0){
        result = (input / 100);
    }else{
        result = (input / 100)+1;
    }

    return result*100;
}

def void getArticle(String artId, String title){
    runStep = "[getArticle]";
    String artUrl = "https://www.dcard.tw/_api/posts/"+artId;
    String artJStr = getJString(artUrl);
    if(artJStr==null){
        return;
    }

    JSONObject artJSON;
    try{
        artJSON = new JSONObject(artJStr);
    }catch(Exception e){
        logger.error("Failed to parse api source of article to JSONObject. url: "+artUrl);
        return;
    }

    String dcUrl = "https://www.dcard.tw/f/"+board+"/p/"+artId;
    String author = getAuthor(artJSON);

    String content = cleanContent(artJSON.opt("content"));
    int likeCount = artJSON.opt("likeCount");
    Timestamp articleDate =  new Timestamp (dateFormat(artJSON.opt("createdAt")).getTime());

    HashMap output = save(artId, site, artId, null, dcUrl, title, author, content, likeCount, articleDate);
    outputList.add(output);

}

def String cleanContent(String input){
    String result = "";
    if(input==null){
        return input;
    }

    Reader reader = new StringReader(input);
    StringBuilder sb = new StringBuilder();
    String line = null;
    while ((line = reader.readLine()) != null) {
        if(!isUrl(line)){
            sb.append(line + "\n");
        }
    }

    reader.close();
    result = toUtf8(sb.toString());

    return result;
}

def boolean isUrl(String input){
    boolean result = false;
    Pattern pattern = Pattern.compile("^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]");
    Matcher m = pattern.matcher(input);
    result = m.find();

    return result;
}

def String getAuthor(input){
    String result = "匿名";
    String name;
    String detail;
    String gender;
    if(input.opt("school")!=null){
        name = input.opt("school")+"．";
    }else{
        name = "匿名．";
    }

    if(input.opt("department")!=null){
        detail = input.opt("department")+"．";
    }else{
        detail = "Unknow．";
    }

    if(input.opt("gender")!=null){
        gender = "性別:"+input.opt("gender")
    }else{
        gender = "性別:Unknow";
    }

    result = name+detail+gender;
    return result;
}

def Date dateFormat(String input){
    Date result = new Date();
    try{
        if(input.length() == 10){
            result = jobtxDf.parse(input);
        }else if(input.length() == 24){
            result = postDf1.parse(input)
        }
    }catch(Exception e){
        logger.error(getStackTrace(e));
    }

    return result;
}

def HashMap save(String postid, String site, String rid, String pid, String pageurl, String postTitle,String authorName, String content, int likeCount, Timestamp articleDate) {
    HashMap result = new HashMap();
    result.put("postid", MD5(postid));
    result.put("site", site);
    result.put("rid", MD5(rid));
    result.put("pid", MD5(pid));
    result.put("pageurl", pageurl);
    result.put("postTitle", splitMessage(postTitle));
    result.put("authorName", splitMessage(authorName));
    result.put("content", splitMessage(content));
    result.put("likeCount",  likeCount);
    result.put("articleDate", articleDate);

    return result;
}

def boolean checkTime(Date input) {
    boolean result = false;
    if (input.getTime() - dateFormat(jobtxdate).getTime() > 0) {
        result = true;
    }

    return result;
}

def String MD5(String input) {
    if (input == null)
        return null;
    try {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(input.getBytes());
        String md5 = new BigInteger(1, md.digest()).toString(16);
        return fillMD5(md5);
    } catch (Exception e) {
        throw new RuntimeException("MD5 failed: " + e.getMessage(), e);
    }
}

def String fillMD5(String md5) {
    return md5.length() == 32 ? md5 : fillMD5("0" + md5);
}

def String splitMessage(String input) {
    String result = "";

    if(input==null){
        return input;
    }

    for (String token: input.split("\u0000")) {
        result = result + token;
    }

    return result;
}

def Document getDoc(String url){
    runUrl = url;
    Document result = null;
    try{
        URL u = new URL(url);
        int status;
        if ("https".equalsIgnoreCase(u.getProtocol())) {
            ignoreSsl();
        }
        URLConnection conn = getConnection(u);
        status = ((HttpURLConnection) conn).getResponseCode();
        InputStream streamSource = null;
        if (status == 200) {
            streamSource = conn.getInputStream();
        } else {
            for (int i = 0; i < 3; i++) {
                Thread.sleep(5000);
                conn = getConnection(u);
                status = ((HttpURLConnection) conn).getResponseCode();
                if (status == 200) {
                    streamSource = conn.getInputStream();
                    break;
                } else {
                    streamSource = ((HttpURLConnection) conn).getErrorStream();
                }
            }
        }

        if(status != 200){
            println runStep;
            println "Failed to get document.";
            String errorMess = Jsoup.parse(parseSrc(streamSource).toString()).text();
            println "Status: "+status+" Message: "+errorMess;
            println "url: "+url;
            return result;
        }

        result = Jsoup.parse(parseSrc(streamSource));
        Thread.sleep(5000);
        return result;
    }catch(Exception e){
        println "Failed to get document.";
        logger.error(getStackTrace(e));
    }
}

def String getJString(String url){
    runUrl = url;
    String result = null;
    try{
        URL u = new URL(url);
        int status;
        if ("https".equalsIgnoreCase(u.getProtocol())) {
            ignoreSsl();
        }
        URLConnection conn = getConnection(u);
        status = ((HttpURLConnection) conn).getResponseCode();
        InputStream streamSource = null;
        if (status == 200) {
            streamSource = conn.getInputStream();
        } else {
            for (int i = 0; i < 3; i++) {
                Thread.sleep(5000);
                conn = getConnection(u);
                status = ((HttpURLConnection) conn).getResponseCode();
                if (status == 200) {
                    streamSource = conn.getInputStream();
                    break;
                } else {
                    streamSource = ((HttpURLConnection) conn).getErrorStream();
                }
            }
        }

        if(status != 200){
            println runStep;
            println "Failed to get document.";
            String errorMess = Jsoup.parse(parseSrc(streamSource).toString()).text();
            println "Status: "+status+" Message: "+errorMess;
            println "url: "+url;
            return result;
        }

        result = parseSrc(streamSource);
        Thread.sleep(5000);
        return result;
    }catch(Exception e){
        println "Failed to get document.";
        logger.error(getStackTrace(e));
    }
}

def String parseSrc(InputStream input){
    String result = null;
    BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
    StringBuilder sb = new StringBuilder();
    String line = null;
    while ((line = reader.readLine()) != null) {
        sb.append(line + "\n");
    }
    reader.close();
    result = toUtf8(sb.toString());

    return result;
}

def URLConnection getConnection(URL u) throws Exception {
    URLConnection conn;
    conn = u.openConnection();
    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
    conn.getHeaderFields();
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(10000);
    return conn;
}

def void ignoreSsl() throws Exception {
    HostnameVerifier hv = new HostnameVerifier() {
                public boolean verify(String urlHostName, SSLSession session) {
                    System.out.println("Warning: URL Host: " + urlHostName + " vs. " + session.getPeerHost());
                    return true;
                }
            };
    trustAllHttpsCertificates();
    HttpsURLConnection.setDefaultHostnameVerifier(hv);
}

def void trustAllHttpsCertificates() throws Exception {
    TrustManager[] trustAllCerts = new TrustManager[1];
    TrustManager tm = new miTM();
    trustAllCerts[0] = tm;
    SSLContext sc = SSLContext.getInstance("SSL");
    sc.init(null, trustAllCerts, null);
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
}

class miTM implements TrustManager, X509TrustManager {
    public X509Certificate[] getAcceptedIssuers() {
        return null;
    }

    public boolean isServerTrusted(X509Certificate[] certs) {
        return true;
    }

    public boolean isClientTrusted(X509Certificate[] certs) {
        return true;
    }

    public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
        return;
    }

    public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
        return;
    }
}

def String toUtf8(String str) throws Exception {
    str = str.replaceAll("\u0000","");
    return new String(str.getBytes("UTF-8"), "UTF-8");
}

def getStackTrace(e){
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    return sw.toString();
}