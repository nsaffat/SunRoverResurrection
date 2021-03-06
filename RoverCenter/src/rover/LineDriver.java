/*Line Driver
 * Drives straight based on looking at the floor tiles and trying to follow the lines
 */

package rover;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
//import org.opencv.highgui.HighGui;

import tools.DataHandler;

public class LineDriver extends Driver implements Runnable {
	private static final String[] REQUESTED_DATA = {DataTypes.DTYPE_COMMANDERSTRING, DataTypes.DTYPE_WEBCAMIMAGE1};
	private static final int MAXGROUPS = 5;
	private static final int EMPTYGROUP = -1000;
	
	boolean active = true;
	volatile boolean needToProcess = false;
	Mat lastframe = null;
	Mat preprocess = new Mat();
	Mat lines = new Mat();
	byte[][] command = new byte[2][2];
	float targetavgxint = -1;
	boolean needSetReference = true;
	int numLines;
	
	Point pt1 = new Point(), pt2 = new Point();
	
	int[] vertLines = new int[MAXGROUPS];		//Max 10 different vertical groups of lines in picture
	int[] oldVertLines = new int[MAXGROUPS];	//Vertical line groups from last frame
	
	Scalar linecolor = new Scalar(100, 255, 100);	//Draw red lines
	
	public LineDriver(DataHandler dh) {
		super(dh);
		
		/*
		for (int i = 0; i < NUMGROUPS; i++) {
			linegroups.add(new ArrayList<CvPoint>());
		}
		*/
		
		clearArray(vertLines);
		clearArray(oldVertLines);
		
		//HighGui.namedWindow("LineDriver");
		
		Thread t = new Thread(this);
		t.start();
	}

	@Override
	public String[] getDataTypes() {
		return REQUESTED_DATA;
	}

	@Override
	public void recieveData(String arg0, Object arg1) {
		if (arg0.equals(DataTypes.DTYPE_COMMANDERSTRING)) {
			String command = (String) arg1;
			if (command.equals("MOTOR_STOP")) {
				active = false;
			}
			else if (command.equals("MOTOR_UP")) {
				active = true;
			}
		}
		
		else if (arg0.equals(DataTypes.DTYPE_WEBCAMIMAGE1)) {
			System.out.println("Got Frame");
			Mat f = (Mat) arg1;
			lastframe = f;
			needToProcess = true;
		}
	}

	@Override
	public void run() {
		System.out.println("Running");
		while (active) {
			if (needToProcess) {
				needToProcess = false;
				processFrame(lastframe);
			}
		}
	}
	
	private void processFrame(Mat frame) {
		System.out.println("Processing frame");
		
		System.out.println("Need to process is: " + needToProcess);
		
		if (frame == null) {
			System.out.println("Frame null");
			return;
		}
		
		//Preprocess image (color -> grayscale -> canny edge)
		Imgproc.cvtColor(frame, preprocess, Imgproc.COLOR_BGR2GRAY);
		//Imgproc.blur(preprocess, preprocess, new Size(2, 2));
		Imgproc.Canny(preprocess, preprocess, 40, 70, 3, true);
        
        //Perform Probabilistic Hough Transform
        Imgproc.HoughLinesP(preprocess, lines, 1, Math.PI / 180, 100, 50, 10);
        
        //Process each detected line
        float sum_deviance = 0;
        float sumxint = 0;
        
        System.out.println(lines.cols());
        
        numLines = 0;	//Number of vertical lines detected (not number of line groups)
        
        for (int li = 0; li < lines.rows(); li++) {
        	double[] line = lines.get(li, 0);
        	
        	//Put data into points
        	pt1.x = line[0]; pt1.y = line[1];
        	pt2.x = line[2]; pt2.y = line[3];   	
            
            //Make line vectors pointing upwards (note opencv starts counting y from top)
            if (pt1.y < pt2.y) {
            	Point temp = pt1;
            	pt1 = pt2;
            	pt2 = temp;
            }
            
            double xdiff = pt2.x-pt1.x; double ydiff = pt1.y - pt2.y;
            double tandev = xdiff/ydiff;	//Tangent of deviation angle from vertical (heading)
            
            Imgproc.line(preprocess, pt1, pt2, linecolor, 2); // draw the segment on the image
                        
            //Remove extremes
            if (Math.abs(tandev) < 1) {
            	/*
                System.out.println("Line spotted: ");
                System.out.println("\t pt1: " + pt1);
                System.out.println("\t pt2: " + pt2);
                */
                
                sum_deviance += tandev;
                
                numLines++;
                
                //Find x intercept of line with top of screen
                int xint  = ((int) (pt2.x + (pt2.y*tandev)));
                
                System.out.println("Line spotted with intercept: " + xint);
                
                //Match line to vertical group, else make new group
                boolean sorted = false;
                for (int group = 0; group < MAXGROUPS; group++) {
                	if (Math.abs(vertLines[group] - xint) < 25) {
                		sorted = true;
                	}
                }
                
                //If line does not fit in group, add to new group
                if (!sorted) {
                	int groupnum = 0;
                	while (groupnum < MAXGROUPS) {
                		if (vertLines[groupnum] == EMPTYGROUP)
                			vertLines[groupnum] = xint;
                		else
                			groupnum++;
                	}
                }
            }
        }
        
        
        //If can't see anything don't do anything
        if (lines.total() == 0) {
           	//Do nothing
        	return;
        }
        
        //Map lines onto old lines and find difference
        float offset = 0;
        int nummatches = 0;
        for (double line : vertLines) {
        	if (line == EMPTYGROUP)
        		continue;
        	for (double oldline : oldVertLines) {
        		if (oldline == EMPTYGROUP)
        			continue;
        		
        		double diff = line - oldline;
        		if (Math.abs(diff) < 100) {
        			offset += diff;
        			nummatches++;
        		}
        	}
        }
        offset /= nummatches;
        
        /*
        //Reset groups
        int[] temp = oldVertLines;
        oldVertLines = vertLines;
        vertLines = temp;
        clearArray(vertLines);*/
        
        if (needSetReference) {
        	System.arraycopy(vertLines, 0, oldVertLines, 0, MAXGROUPS);
        	needSetReference = false;
        }
        
        clearArray(vertLines);
        
        //System.out.println("LD: " + vertGroundLines.size());
        if (numLines != 0) {
        	System.out.println("LD: Offset " + offset);
        
	        float headingcorrection = (float) (-Math.atan(sum_deviance/numLines));
	        
	        float avgxint = sumxint/lines.total();
	        float xintcorrection = targetavgxint - avgxint;
	        
	        System.out.println("Heading correction: "+ headingcorrection);
	        /*
	        if (targetavgxint == -1) {
	        	targetavgxint = avgxint;
	        }
	        else if (offset > 0) {
	        	command[0][0] = (byte) 127;
	        	command[0][1] = (byte) (127-offset*3);
	        	command[1] = command[0];
	        }
	        else {
	        	command[0][0] = (byte) (127+offset*3);
	        	command[0][1] = (byte) 127;
	        	command[1] = command[0];
	        }
	        */
	        
	        if (headingcorrection == 0) {
	        	command[0][0] = (byte) (127);
	        	command[0][1] = (byte) 127;
	        	command[1] = command[0];
	        }
	        else if (headingcorrection < 0) {
	        	command[0][0] = (byte) 0; // (127+headingcorrection*100);
	        	command[0][1] = (byte) 127;
	        	command[1] = command[0];
	        }
	        else if (headingcorrection > 0) {
	        	command[0][1] = (byte) 0; // (127-headingcorrection*100);
	        	command[0][0] = (byte) 127;
	        	command[1] = command[0];
	        }
	        
	        //Send command to steer
	        sendMotorVals(command);
        }
	        
        //Display preprocessed image with the vertical lines drawn onto it
        //HighGui.imshow("LineDriver", preprocess);
        //HighGui.waitKey(50);
        System.out.println("Displaying");
	}
	
	/*Overwrite array with EMPTYGROUP values (for use with line groups)*/
	private void clearArray(int[] arr) {
		for (int i = 0; i < arr.length; i++)
			arr[i] = EMPTYGROUP;
	}
}
