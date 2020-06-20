import cpww.CPWW;
//
// import NERAnnotation_test;
import java.io.File;
import java.io.IOException;

public class RunModel_test {
    public static void main(String[] args) throws IOException {
//        File file = new File("/Users/hejiecui/Developer/Research/CPWW/Data/docs");
        File file = new File("/Users/hejiecui/Developer/Research/TREC-COVID/cord-rnd3/sub10_periods_split");
        String[] filelist = file.list();
        for (int i = 0; i < filelist.length; i++) {
            //System.out.println(filelist[i]);
            NERAnnotation_test.Ann(filelist[i]);
            CPWW.call(filelist[i]);
        }
    }
}
