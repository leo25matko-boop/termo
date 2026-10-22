package se.automatiziraj.termodom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small native line icons; no image or browser runtime is involved. */
public final class NavIconView extends View {
    private final int icon,color;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    public NavIconView(Context context,int which,int tint){super(context);icon=which;color=tint;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);c.save();float scale=Math.min(getWidth(),getHeight())/24f;c.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2);c.scale(scale,scale);
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        Path path=new Path();
        switch(icon){
            case 0: path.moveTo(3,10);path.lineTo(12,3);path.lineTo(21,10);path.moveTo(5,9);path.lineTo(5,21);path.lineTo(19,21);path.lineTo(19,9);path.moveTo(9,21);path.lineTo(9,14);path.lineTo(15,14);path.lineTo(15,21);break;
            case 1: path.moveTo(3,7);path.lineTo(12,2);path.lineTo(21,7);path.lineTo(12,12);path.close();path.moveTo(3,12);path.lineTo(12,17);path.lineTo(21,12);path.moveTo(3,17);path.lineTo(12,22);path.lineTo(21,17);break;
            case 2: c.drawRoundRect(3,5,21,21,2,2,p);path.moveTo(7,2);path.lineTo(7,8);path.moveTo(17,2);path.lineTo(17,8);path.moveTo(3,11);path.lineTo(21,11);path.moveTo(7,15);path.lineTo(9,15);path.moveTo(15,15);path.lineTo(17,15);path.moveTo(7,18);path.lineTo(9,18);break;
            case 3: path.moveTo(3,3);path.lineTo(3,21);path.lineTo(22,21);path.moveTo(6,16);path.lineTo(10,11);path.lineTo(14,14);path.lineTo(20,5);break;
            default: c.drawCircle(12,12,4,p);c.drawCircle(12,12,8,p);for(int n=0;n<8;n++){double a=n*Math.PI/4;path.moveTo(12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8);path.lineTo(12+(float)Math.cos(a)*11,12+(float)Math.sin(a)*11);}break;
        }
        c.drawPath(path,p);c.restore();
    }
}
