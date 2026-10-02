export default function handler(req,res){
  res.setHeader('Cache-Control','no-store');
  res.status(200).json({ok:true,providers:{openrouter:!!process.env.OPENROUTER_API_KEY,groq:!!process.env.GROQ_API_KEY,gemini:!!process.env.GEMINI_API_KEY,ocrspace:!!process.env.OCR_SPACE_API_KEY}});
}

