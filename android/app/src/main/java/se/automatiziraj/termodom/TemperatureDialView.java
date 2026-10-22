package se.automatiziraj.termodom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Locale;

/** A temperature gauge drawn directly by Android Canvas. */
public final class TemperatureDialView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double temperature;
    public TemperatureDialView(Context context,double value){super(context);temperature=value;setContentDescription("Temperatura "+(Double.isFinite(value)?String.format(Locale.ROOT,"%.1f °C",value):"nije dostupna"));}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float density=getResources().getDisplayMetrics().density;
        float size=Math.min(getWidth(),getHeight()),cx=getWidth()/2f,cy=getHeight()/2f;
        float radius=size/2-12*density;
        RectF circle=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(9*density);paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xffedf0e7);canvas.drawArc(circle,140,260,false,paint);
        paint.setColor(0xff96ad71);canvas.drawArc(circle,140,Double.isFinite(temperature)?(float)(260*Math.max(0,Math.min(1,temperature/40))):0,false,paint);
        paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        paint.setColor(0xff254c3d);paint.setTextSize(36*density);
        canvas.drawText(Double.isFinite(temperature)?String.format(Locale.forLanguageTag("hr-HR"),"%.1f°",temperature):"—",cx,cy+7*density,paint);
        paint.setColor(0xff8a9a85);paint.setTextSize(8*density);
        canvas.drawText("TRENUTNA TEMPERATURA",cx,cy+27*density,paint);
    }
}
