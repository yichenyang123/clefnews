package clef.newsreel;

import java.util.*;
import clef.newsreel.DataLoader.ItemUpdate;
import clef.newsreel.DataLoader.ClickEvent;
import clef.newsreel.DataLoader.RecommendationReq;
import clef.newsreel.DataLoader.KeyWordsObject;
import java.util.LinkedList;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
// $example on$
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.mllib.linalg.Matrix;
import org.apache.spark.mllib.linalg.SingularValueDecomposition;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.linalg.distributed.RowMatrix;
// $example off$

/**
 * Created by gram on 23.03.17.
 */
public class Main {

    static DataLoader dataloader = new DataLoader();
    static Datastore datastore = new Datastore();
    static Recommender recommender = new Recommender(datastore);

    public static void main(String[] args){


        loadData(false);
        ArrayList<int[]> ratingSparseMatrix =  datastore.getArticlesRead(20, 2);
        int nbArticles = datastore.getNoOfArticles();


        /**
        for (int[] userLine : ratingSparseMatrix){
            System.out.println(Arrays.toString(userLine));
        }
        **/
        getPcaSvdRowList(ratingSparseMatrix, nbArticles);

        runSVD(1000, ratingSparseMatrix, nbArticles);
        //runPCA(50, ratingSparseMatrix, nbArticles);

        //evaluation(datastore);

    }


    public static void runSVD(int dim, ArrayList<int[]> ratingSparseMatrix, int nbArticles){

        SparkConf conf = new SparkConf().setAppName("SVD collaborative filtering");
        SparkContext sc = new SparkContext(conf);
        JavaSparkContext jsc = JavaSparkContext.fromSparkContext(sc);

        JavaRDD<Vector> rows = jsc.parallelize(getPcaSvdRowList(ratingSparseMatrix, nbArticles));

        // Create a RowMatrix from JavaRDD<Vector>.
        RowMatrix mat = new RowMatrix(rows.rdd());

        // Compute the top 3 singular values and corresponding singular vectors.
        SingularValueDecomposition<RowMatrix, Matrix> svd = mat.computeSVD(dim, true, 1.0E-9d);
        //RowMatrix U = svd.U();
        //Vector s = svd.s();
        Matrix V = svd.V();

        /*
        Vector[] collectPartitions = (Vector[]) U.rows().collect();
        System.out.println("U factor is:");
        for (Vector vector : collectPartitions) {
            System.out.println("\t" + vector);
        }
        System.out.println("Singular values are: " + s);
         */

        System.out.println("SVD V component is:\n");
        ArrayList<double[]> result = transformResults(V);
        for(double[] r : result){
            System.out.println(Arrays.toString(r));
        }
        System.out.println("for dims" + V.numRows() + " x " +V.numCols());

        jsc.stop();
    }


    public static void runPCA(int dim, ArrayList<int[]> ratingSparseMatrix, int nbArticles){

        SparkConf conf = new SparkConf().setAppName("PCA dim reduction");
        SparkContext sc = new SparkContext(conf);
        JavaSparkContext jsc = JavaSparkContext.fromSparkContext(sc);


        JavaRDD<Vector> rows = jsc.parallelize(getPcaSvdRowList(ratingSparseMatrix, nbArticles));

        // Create a RowMatrix from JavaRDD<Vector>.
        RowMatrix mat = new RowMatrix(rows.rdd());

        // Compute the top 3 principal components.
        Matrix pc = mat.computePrincipalComponents(dim);
        //RowMatrix projected = mat.multiply(pc);
        System.out.println("PCA factor is:\n");
        ArrayList<double[]> result = transformResults(pc);
        for(double[] r : result){
            System.out.println(Arrays.toString(r));
        }
        System.out.println("for dims" + pc.numRows() + " x " +pc.numCols());


    }

    private static LinkedList<Vector> getPcaSvdRowList(ArrayList<int[]> ratingSparseMatrix, int nbArticles){
        double sparsity = 0;
        LinkedList<Vector> rowsList = new LinkedList<Vector>();
        for (int[] userRowIndexes : ratingSparseMatrix) {

            double[] values = new double[userRowIndexes.length];
            for(int i = 0; i < values.length; i++){ values[i] = 1.0; }

            Vector currentRow = Vectors.sparse(nbArticles, userRowIndexes, values);
            System.out.println(currentRow);
            rowsList.add(currentRow);
            sparsity += userRowIndexes.length;
            System.out.println("Sparisity now " + sparsity);
        }

        sparsity = 1 - (sparsity / (ratingSparseMatrix.size() * nbArticles));
        System.out.println("Matrix is:" + ratingSparseMatrix.size() +" x " + nbArticles);

        System.out.println("The sparsity of data = " + sparsity);

        return rowsList;

    }

    private static ArrayList<double[]> transformResults(Matrix m){
        ArrayList<double[]> resultMatrix = new ArrayList<double[]>();
        double[] arrayM = m.toArray();
        for(int r = 0; r < m.numRows(); r++){
            double[] row = new double[m.numCols()];
            for(int c = 0; c < m.numCols(); c++) {
                int index = m.numCols()*r + c;
                row[c] = arrayM[index];
            }
            resultMatrix.add(row);
        }
        return resultMatrix;


    }







    public static void loadData(boolean includeUnkownItems){
        String filePathLog = "/export/b/home/lemeiz/clefnew/idomaar/datastreammanager/input/newsreel-test/2017-NewsREEL/";
        String filePathSer = "/export/b/home/lemeiz/clefnew/idomaar/datastreammanager/input/newsreel-test/2017-NewsREEL/";
        //String filePathLog = "/home/havikbot/Documents/CLEFdata/";
        //String filePathSer = "/home/havikbot/Documents/CLEFdata/";

        int[] fileNumbers = {1,6};  // {1,1} for 2016-02-01.log,
        // {1,3} for (2016-02-01.log + 2016-02-02.log + 2016-02-03.log) etc.


        ArrayList<Object> dataStream = dataloader.loadDataStream(filePathLog, filePathSer, fileNumbers);

        double count = 0;
        double size = dataStream.size();
        double lastProg = 0;

        KeyWordsObject keyWordsObject = null;

        for(Object event : dataStream) {
            if (event instanceof KeyWordsObject) {
                keyWordsObject = (KeyWordsObject) event;
            } else if (event instanceof ItemUpdate) {
                datastore.registerArticle((ItemUpdate) event);
            } else if (event instanceof RecommendationReq) {
                datastore.registerRecommendationReq((RecommendationReq) event, recommender, includeUnkownItems, keyWordsObject);
            } else if (event instanceof ClickEvent) {
                datastore.registerClickEvent((ClickEvent) event);
            }
            double progress = (int) (20 * count / size);
            if(progress != lastProg) {
                System.out.print(5*progress + "% ");
                lastProg = progress;
            }
            count++;
        }
        System.out.println("Complete");

        System.out.println("keywords size:" + datastore.all_keywords.size());
        System.out.println("articles size:");
        for(long dKey : datastore.domains.keySet()){
            System.out.println("  domain:" + dKey+" : " + datastore.domains.get(dKey).articles.size());
        }


    }


    public static void evaluation(Datastore datastore){
        System.out.println("Running Evaluation");
        int[] overallScores = new int[3];   //[nb_recs, nb_clicks, nb_sucsessfullRecs]
        for(Long domainID : datastore.domains.keySet()){
            int[] domainScores = new int[3];
            int nb_users = 0;
            for(Long userID : datastore.users.keySet()){
                if(datastore.users.get(userID).statistics.containsKey(domainID)){
                    domainScores[0] += datastore.users.get(userID).statistics.get(domainID).nb_recommendationRequests;
                    domainScores[1] += datastore.users.get(userID).statistics.get(domainID).nb_clicks;
                    domainScores[2] += datastore.users.get(userID).statistics.get(domainID).nb_sucssessfullRecs;
                    nb_users++;
                }
            }
            calcAndPrintScores(domainScores, "For Domain: " + domainID);
            for(int i = 0; i < 3; i++){
                overallScores[i] += domainScores[i];
            }
        }
        calcAndPrintScores(overallScores, "For Entire dataset:");
    }


    public static void calcAndPrintScores(int[] scores, String title){
        double percentAllreqs = 100*(double)scores[2] / (double) scores[0];
        double percentClicks = 100*(double)scores[2] / (double) scores[1];
        System.out.println(fixedLengthString(title, 20) + "\t" +
                fixedLengthString("[" +scores[0] +", " + scores[1] + ", " + scores[2] +" ] ", 20) + "\t" +
                "[" + String.format( "%.5f", percentAllreqs) +", " + String.format( "%.5f", percentClicks) + "]");

    }

    public static String fixedLengthString(String string, int length) {
        return String.format("%1$"+length+ "s", string);
    }


    public void printUserPerArticleCount(){
        HashMap<Integer, Integer> article_counts = new HashMap<Integer, Integer>();
        for(long dKey : datastore.domains.keySet()){
            for(long itemID : datastore.domains.get(dKey).articles.keySet()){
                Article article = datastore.domains.get(dKey).articles.get(itemID);
                Integer user_count = article.user_visited.size();
                if(!article_counts.containsKey(user_count)){article_counts.put(user_count, 0);}
                article_counts.put(user_count, article_counts.get(user_count) + 1);
            }
        }
        List sortedKeys=new ArrayList(article_counts.keySet());
        Collections.sort(sortedKeys);
        for(Object k : sortedKeys){
            System.out.println( k + "\t" + article_counts.get(k));
        }
    }






}
