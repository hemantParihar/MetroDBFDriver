package com.dbf.utility;

public class FunUtility {
	public static String repeat(String str, int len) {
	    StringBuilder sb = new StringBuilder(len * str.length());
	    for (int i = 0; i < len; i++) {
	        sb.append(str);
	    }
	    return sb.toString();
	}
}
