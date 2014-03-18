/**
 * Copyright 2012 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.codesourcery.springmass.springmass;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferStrategy;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.badlogic.gdx.math.Vector3;

import de.codesourcery.springmass.math.VectorUtils;

public final class RenderPanel extends Canvas implements IRenderPanel {

    private final Object SIMULATION_LOCK = new Object();

    // @GuardedBy( SIMULATION_LOCK )
    private SpringMassSystem system;

    // @GuardedBy( SIMULATION_LOCK )
    private SimulationParameters parameters;

    private final Object BUFFER_LOCK = new Object();

    private volatile boolean bufferCreated = false;

    private final RenderThread renderThread = new RenderThread();

    protected final class RenderThread extends Thread 
    {
        private volatile float desiredFps;
        private volatile int delay=20;
        private volatile boolean debugPerformance=false;        

        private final CountDownLatch latch=new CountDownLatch(1);
        private volatile boolean terminate;

        private final Object COUNTER_LOCK = new Object();

        // @GuardedBy( COUNTER_LOCK )
        private long frameCounter=0;

        // @GuardedBy( COUNTER_LOCK )        
        private long minTime=Long.MAX_VALUE;

        // @GuardedBy( COUNTER_LOCK )        
        private long sumTime;        

        // @GuardedBy( COUNTER_LOCK )        
        private long maxTime=Long.MIN_VALUE;  
        
        // @GuardedBy( COUNTER_LOCK ) 
        private long statResetTime=System.currentTimeMillis();

        // @GuardedBy( COUNTER_LOCK ) 
        private float currentAvgFPS=0;
        
        public RenderThread() 
        {
            setName("rendering-thread");
            setDaemon(true);
        }

        public void parametersChanged() 
        {
            synchronized (COUNTER_LOCK) 
            {
                this.desiredFps = parameters.getDesiredFPS();
                this.delay = Math.round( 1000.0f / this.desiredFps );
                this.debugPerformance = parameters.isDebugPerformance();
                
                minTime=Long.MAX_VALUE;
                maxTime=Long.MIN_VALUE;                   
                frameCounter=0;
                currentAvgFPS = 0;
                sumTime=0;
                statResetTime=System.currentTimeMillis();                
            }
        }

        public void shutdown() throws InterruptedException 
        {
            terminate = true;
            latch.await();
        }

        @Override
        public void run()
        {
            try 
            {
                while ( ! terminate ) 
                {
                    long time = -System.currentTimeMillis();
                    boolean rendered = false;
                    try 
                    {
                        rendered = renderFrame(currentAvgFPS);
                    } 
                    catch(Exception e) 
                    {
                        e.printStackTrace();
                    }
                    time += System.currentTimeMillis();

                    if ( rendered ) 
                    {
                        synchronized (COUNTER_LOCK) 
                        {
                            frameCounter++;

                            minTime = Math.min(time,minTime);
                            maxTime = Math.max(time,maxTime);
                            sumTime += time;
                            
                            // adjust delay based on avg FPS
                            final float timeDelta = (System.currentTimeMillis() - statResetTime)/1000.0f; 
                            currentAvgFPS = frameCounter / timeDelta;
                            final float delta = desiredFps-currentAvgFPS;
                            if ( Math.abs( delta ) > 2 ) 
                            {
                                if ( delta > 0 ) 
                                {
                                    if ( delay > 0 ) {
                                        delay--;
//                                        System.out.println("*** New delay: "+delay+" , desired FPS:  "+desiredFps+" , current FPS: "+currentAvgFPS);                                        
                                    }
                                } else {
                                    delay++;
//                                    System.out.println("*** New delay: "+delay+" , desired FPS:  "+desiredFps+" , current FPS: "+currentAvgFPS);
                                }
                            }

                            if ( debugPerformance && (frameCounter%30) == 0 ) 
                            {
                                final float avgTime = sumTime / (float) frameCounter;
                                final float avgFps = 1000.0f / avgTime;
                                final float maxFps = 1000.0f / minTime;
                                final float minFps = 1000.0f / maxTime;
                                
                                System.out.println("frames "+frameCounter+" , current "+time+" ms / "+
                                        " min: "+minTime+" ms / avg: "+avgTime+" ms / max: "+maxTime+" ms (FPS: "+minFps+" / "+avgFps+" / "+maxFps+" )");
                            }
                        }
                    }

                    // sleep some time
                    if ( delay > 0 ) 
                    {
                        try { Thread.sleep(delay); } 
                        catch(Exception e) 
                        {
                            e.printStackTrace();
                        }
                    }
                }
            } 
            finally {
                latch.countDown();
            }
        }    
    };

    public RenderPanel() 
    {
        setFocusable(true);
        addComponentListener( new ComponentAdapter() 
        {
            @Override
            public void componentShown(ComponentEvent e) 
            {
                synchronized(BUFFER_LOCK) 
                {
                    createBufferStrategy(3);
                    bufferCreated = true;
                }
            }

            @Override
            public void componentResized(ComponentEvent e) 
            {
                synchronized(BUFFER_LOCK) 
                {
                    createBufferStrategy(3);
                    bufferCreated = true;
                }
            }
        });
    }

    /* (non-Javadoc)
	 * @see de.codesourcery.springmass.springmass.IRenderPanel#setSimulator(de.codesourcery.springmass.springmass.Simulator)
	 */
    @Override
	public void setSimulator(Simulator simulator) 
    {
        synchronized (SIMULATION_LOCK) 
        {
            this.system = simulator.getSpringMassSystem().createCopy();
            this.parameters = simulator.getSimulationParameters();
            this.renderThread.parametersChanged();
        }
    }

    /* (non-Javadoc)
	 * @see de.codesourcery.springmass.springmass.IRenderPanel#addTo(java.awt.Container)
	 */
    @Override
	public void addTo(Container container) 
    {
        container.add( this );

        if ( ! this.renderThread.isAlive() ) 
        {
            this.renderThread.start();
        }
    }

    /* (non-Javadoc)
	 * @see de.codesourcery.springmass.springmass.IRenderPanel#viewToModel(int, int)
	 */
    @Override
	public Vector3 viewToModel(int x,int y) {

        float scaleX = getWidth() / (float) parameters.getXResolution();
        float scaleY = getHeight() / (float) parameters.getYResolution();
        return new Vector3( x / scaleX , y / scaleY , 0 );
    }

    /* (non-Javadoc)
	 * @see de.codesourcery.springmass.springmass.IRenderPanel#modelToView(de.codesourcery.springmass.math.Vector4)
	 */
    @Override
	public Point modelToView(Vector3 vec) 
    {
        double scaleX = getWidth() / (double) parameters.getXResolution();
        double scaleY = getHeight() / (double) parameters.getYResolution();
        return modelToView( vec , scaleX , scaleY ); 
    }

    /* (non-Javadoc)
	 * @see de.codesourcery.springmass.springmass.IRenderPanel#modelToView(de.codesourcery.springmass.math.Vector4, double, double)
	 */
    @Override
	public Point modelToView(Vector3 vec,double scaleX,double scaleY) 
    {
        return new Point( (int) Math.round( vec.x * scaleX ) , 48 - (int) Math.round( vec.y * scaleY ) );
    }		

    protected final class Triangle implements Comparable<Triangle> {

        private final Vector3 p0;
        private final Vector3 p1;
        private final Vector3 p2;
        private final double z;

        public Triangle(Vector3 p0,Vector3 p1,Vector3 p2) {
            this.p0 = p0;
            this.p1 = p1;
            this.p2 = p2;
            this.z = (p0.z+p1.z+p2.z)/3;
        }

        public boolean noSideExceedsLengthSquared(double lengthSquared) 
        {
            return p0.dst2( p1 ) <= lengthSquared && p0.dst2( p2 ) <= lengthSquared;
        }

        @Override
        public int compareTo(Triangle o) 
        {
            if ( this.z > o.z ) {
                return -1;
            }
            if ( this.z < o.z ) {
                return 1;
            }
            return 0;
        }

        public Vector3 getSurfaceNormal() 
        {
            Vector3 v1 = new Vector3(p1).sub( p0 );
            Vector3 v2 = new Vector3(p2).sub( p0 );
            return v2.crs( v1 ).nor();
        }

        public Vector3 calculateLightVector(Vector3 lightPos) {
            return new Vector3(lightPos).sub(p0).nor();
        }

        public void getViewCoordinates(int[] pointX,int[] pointY)
        {
            Point p = modelToView( p0 );
            pointX[0] = p.x;
            pointY[0] = p.y;

            p = modelToView( p1 );
            pointX[1] = p.x;
            pointY[1] = p.y;	

            p = modelToView( p2 );
            pointX[2] = p.x;
            pointY[2] = p.y;	
        }

        public Color calculateSurfaceColor(Vector3 lightPos,Vector3 lightColor) 
        {
            Vector3 normal = getSurfaceNormal();
            Vector3 lightVector = calculateLightVector( lightPos );

            final float angle = Math.abs( normal.dot( lightVector ) );
            return VectorUtils.toColor( new Vector3(lightColor).scl( angle ) );
        }
    }	
    
    /* (non-Javadoc)
	 * @see de.codesourcery.springmass.springmass.IRenderPanel#modelChanged()
	 */
    @Override
	public void modelChanged() 
    {
        synchronized (SIMULATION_LOCK) 
        {                
            this.system.updateFromOriginal();
            if ( this.parameters.isWaitForVSync() ) 
            {
                try {
                    SIMULATION_LOCK.wait();
                } 
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }        
    }

    public boolean renderFrame(float currentFPS) 
    {
        synchronized( BUFFER_LOCK ) 
        {
            if ( !bufferCreated ) 
            {
                return false;
            }

            final BufferStrategy strategy = getBufferStrategy();
            final Graphics graphics = strategy.getDrawGraphics();
            try 
            {
                final SimulationParameters params;
                final SpringMassSystem sys;
                synchronized (SIMULATION_LOCK) 
                {                
                    params = this.parameters;
                    sys = this.system;
                    render( graphics , sys , params  , currentFPS );
                    SIMULATION_LOCK.notifyAll();
                }
            } 
            finally 
            {
                graphics.dispose();
            }
            strategy.show();
        }
        Toolkit.getDefaultToolkit().sync();
        return true;
    }
    
    private final DecimalFormat FPS_FORMAT = new DecimalFormat("###0.00");

    private void render(Graphics g,SpringMassSystem system,SimulationParameters parameters,float currentAvgFPS) 
    {
        // clear image
        g.setColor( getBackground() );
        g.fillRect( 0 , 0 , getWidth() , getHeight() );
        
        g.setColor( Color.WHITE );
        
        g.drawString( "Avg. FPS: "+FPS_FORMAT.format( currentAvgFPS ) , 5, 15 );
        
        g.drawString("Left-click to drag cloth | Right-click to pin/unpin particles | Set max. spring length > 0 to enable tearing"  , 5, getHeight()-15 );
        
        final double scaleX = getWidth() / (double) parameters.getXResolution();
        final double scaleY = getHeight() / (double) parameters.getYResolution();

        final int boxWidthUnits = 5;

        final int boxWidthPixels = (int) Math.round( boxWidthUnits * scaleX );
        final int boxHeightPixels = (int) Math.round( boxWidthUnits * scaleY );

        final int halfBoxWidthPixels = (int) Math.round( boxWidthPixels / 2.0 );
        final int halfBoxHeightPixels = (int) Math.round( boxHeightPixels / 2.0 );

        if ( parameters.isLightSurfaces() ) 
        {
            final int rows = parameters.getGridRowCount();
            final int columns = parameters.getGridColumnCount();

            final List<Triangle> triangles = new ArrayList<>( rows*columns*2 );
            final boolean checkArea = parameters.getMaxSpringLength() > 0;
            final double maxLenSquared = parameters.getMaxSpringLength()*parameters.getMaxSpringLength();

            final Mass[][] masses = system.getMassArray();
            for ( int y = 0 ; y < rows-1 ; y++) 
            {
                for ( int x = 0 ; x < columns-1 ; x++) 
                {
                    Mass m0 = masses[x][y];
                    Mass m1 = masses[x+1][y];
                    Mass m2 = masses[x][y+1];
                    Mass m3 = masses[x+1][y+1];

                    Vector3 p0 = m0.currentPosition;
                    Vector3 p1 = m1.currentPosition;
                    Vector3 p2 = m2.currentPosition;
                    Vector3 p3 = m3.currentPosition;

                    Triangle t1 = new Triangle(p0,p1,p2);
                    Triangle t2 = new Triangle(p1,p3,p2);							
                    if ( checkArea ) {
                        if ( t1.noSideExceedsLengthSquared( maxLenSquared ) ) {
                            triangles.add( t1 );
                        }
                        if ( t2.noSideExceedsLengthSquared( maxLenSquared ) ) {
                            triangles.add( t2 );
                        }
                    } else {
                        triangles.add( t1 );
                        triangles.add( t2 );
                    }
                }
            }

            // sort by Z-coordinate and draw from back to front
            Collections.sort( triangles );

            final int[] pointX = new int[3];
            final int[] pointY = new int[3];					
            for ( Triangle t : triangles ) 
            {
                Color color = t.calculateSurfaceColor( parameters.getLightPosition() , parameters.getLightColor() );
                t.getViewCoordinates(pointX,pointY);
                g.setColor(color);
                g.fillPolygon(pointX,pointY,3); 
            }
        }

        if ( parameters.isRenderMasses() ) 
        {
        	for ( int y = 0 ; y < parameters.getGridRowCount() ; y++ ) 
        	{
            	for ( int x = 0 ; x < parameters.getGridColumnCount() ; x++ ) 
            	{
            		final Mass m = system.massArray[x][y];
                    final Point p = modelToView( m.currentPosition , scaleX , scaleY );
                    if ( m.isSelected() ) 
                    {
                        g.setColor(Color.RED );
                        g.drawRect( p.x - halfBoxWidthPixels , p.y - halfBoxHeightPixels , boxWidthPixels , boxHeightPixels );
                        g.setColor(Color.BLUE);
                    } 
                    else 
                    {
                        if ( m.isFixed() ) {
                            g.setColor( Color.BLUE );
                            g.fillRect( p.x - halfBoxWidthPixels , p.y - halfBoxHeightPixels , boxWidthPixels , boxHeightPixels );								
                        } else {
                            g.setColor( m.color );
                            g.drawRect( p.x - halfBoxWidthPixels , p.y - halfBoxHeightPixels , boxWidthPixels , boxHeightPixels );								
                        }
                    }            		
            	}
        	}
        }

        // render springs
        if ( parameters.isRenderSprings() || parameters.isRenderAllSprings() ) 
        {
            g.setColor(Color.GREEN);
            for ( Spring s : system.getSprings() ) 
            {
                if ( s.doRender ) {
                    final Point p1 = modelToView( s.m1.currentPosition );
                    final Point p2 = modelToView( s.m2.currentPosition );
                    g.setColor( s.color );
                    g.drawLine( p1.x , p1.y , p2.x , p2.y );
                }
            }
        }
    }	
}