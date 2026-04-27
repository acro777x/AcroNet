package com.ghost.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.ghost.app.databinding.FragmentQrScannerBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult

class QRScannerFragment : Fragment() {

    private var _binding: FragmentQrScannerBinding? = null
    private val binding get() = _binding!!

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startScanning()
        } else {
            Toast.makeText(requireContext(), "Camera required to scan QR", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQrScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mode = arguments?.getString("mode") ?: "scan"
        val shareData = arguments?.getString("shareData") ?: ""

        if (mode == "generate") {
            showMyQR(shareData)
        } else {
            checkCameraAndScan()
        }

        binding.btnScan.setOnClickListener {
            checkCameraAndScan()
        }

        binding.btnGenerate.setOnClickListener {
            // Usually we'd get this from shared prefs or viewmodel
            showMyQR(shareData)
        }
    }

    private fun checkCameraAndScan() {
        binding.ivQrCode.visibility = View.GONE
        binding.barcodeScanner.visibility = View.VISIBLE
        
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanning()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScanning() {
        binding.barcodeScanner.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let { qrData ->
                    Toast.makeText(requireContext(), "Scanned: $qrData", Toast.LENGTH_SHORT).show()
                    // If it's a valid string, we can navigate back to login with the data
                    // Format could be: "GHOST_ROOM:<roomHash>:<key>"
                    if (qrData.startsWith("GHOST_ROOM:")) {
                        val parts = qrData.split(":")
                        if (parts.size == 3) {
                            val roomHash = parts[1]
                            val key = parts[2]
                            // Send data back via parentFragmentManager or Navigation Component
                            val bundle = Bundle().apply {
                                putString("scannedRoom", roomHash)
                                putString("scannedKey", key)
                            }
                            parentFragmentManager.setFragmentResult("qrScanResult", bundle)
                            parentFragmentManager.popBackStack()
                        }
                    } else {
                        Toast.makeText(requireContext(), "Invalid Ghost Room QR", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>?) {}
        })
        binding.barcodeScanner.resume()
    }

    private fun showMyQR(data: String) {
        if (data.isEmpty()) {
            Toast.makeText(requireContext(), "No room data to share", Toast.LENGTH_SHORT).show()
            return
        }
        binding.barcodeScanner.pause()
        binding.barcodeScanner.visibility = View.GONE
        binding.ivQrCode.visibility = View.VISIBLE

        try {
            val writer = MultiFormatWriter()
            val bitMatrix: BitMatrix = writer.encode("GHOST_ROOM:$data", BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            binding.ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        if (binding.barcodeScanner.visibility == View.VISIBLE) {
            binding.barcodeScanner.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeScanner.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
