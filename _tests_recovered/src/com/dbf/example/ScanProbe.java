package com.dbf.example;
import com.dbf.jdbc.dbf.DBFReader;
import java.nio.charset.Charset;
public class ScanProbe {
  public static void main(String[] a) throws Exception {
    String f = "E:/METRO/SG20/SALES.DBF"; Charset cs = Charset.forName("Cp1252");
    for (int run=0; run<3; run++){
      // (1) pure record navigation, NO decode
      long t0=System.currentTimeMillis(); long n=0;
      try(DBFReader r=new DBFReader(f,cs)){ r.beforeFirst(); while(r.next()){ if(!r.isDeleted()) n++; } }
      long nav=System.currentTimeMillis()-t0;
      // (2) navigation + decode 1 column (V_NO, find its index)
      t0=System.currentTimeMillis(); long n2=0;
      try(DBFReader r=new DBFReader(f,cs)){
        int vno=-1; var fs=r.getHeader().getFields();
        for(int i=0;i<fs.size();i++) if(fs.get(i).getName().equalsIgnoreCase("V_NO")) vno=i;
        boolean[] need=new boolean[fs.size()]; need[vno]=true;
        r.beforeFirst(); while(r.next()){ if(r.isDeleted())continue; Object[] row=r.getCurrentRowPruned(need); n2++; }
      }
      long dec1=System.currentTimeMillis()-t0;
      System.out.println("run"+run+": nav(no-decode)="+nav+"ms ("+n+" rows)  nav+decode1col="+dec1+"ms");
    }
  }
}
