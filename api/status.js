export default function handler(req,res){
 res.setHeader('Cache-Control','no-store');
 res.status(200).json({ok:true,providers:{google:!!process.env.GEMINI_API_KEY,groq:!!process.env.GROQ_API_KEY,ocrspace:!!process.env.OCR_SPACE_API_KEY,twelvelabs:!!(process.env.TWELVELABS_API_KEY||process.env.TWELVE_LABS_API_KEY)},models:{groqVision:process.env.GROQ_VISION_MODEL||'meta-llama/llama-4-scout-17b-16e-instruct',groqText:process.env.GROQ_TEXT_MODEL||'llama-3.3-70b-versatile',twelvelabs:'pegasus1.5'},limits:{batchImages:2,providerTimeoutSeconds:10,videoTimeoutSeconds:20},pushScheduled:false});
}

