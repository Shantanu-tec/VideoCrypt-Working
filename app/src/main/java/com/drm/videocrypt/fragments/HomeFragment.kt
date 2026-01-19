package com.drm.videocrypt.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.drm.videocrypt.MainActivity
import com.drm.videocrypt.PlayerActivity
import com.drm.videocrypt.databinding.FragmentHomeBinding
import com.appsquadz.educryptmedia.playback.EducryptMedia
import com.appsquadz.educryptmedia.utils.isDownloadExistForVdcId
import com.drm.videocrypt.Const
import com.drm.videocrypt.models.ListItem
import com.drm.videocrypt.utils.SharedPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class HomeFragment : Fragment() {

    private lateinit var binding: FragmentHomeBinding

    private lateinit var educryptMedia: EducryptMedia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentHomeBinding.inflate(inflater,container,false)
        return binding.root
    }


    @UnstableApi
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        educryptMedia = EducryptMedia.getInstance(requireActivity())
        setListener()
    }


    @UnstableApi
    private fun setListener()  = binding.apply{

        touchMeBtn.setOnClickListener {
            if (videoId.text.toString().isEmpty()){
                Toast.makeText(requireActivity(),"Enter Video Id",Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(requireActivity(),PlayerActivity::class.java)
            intent.putExtra("videoId",videoId.text.toString())
            startActivity(intent)
        }

        download.setOnClickListener {
            if (videoId.text.toString().isEmpty()){
                Toast.makeText(requireActivity(),"Enter Video Id",Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (SharedPreference.instance!!.getDownloadData()!=null) {
                val listItems = SharedPreference.instance!!.getDownloadData()!!
                if(requireActivity().isDownloadExistForVdcId(listItems.vdcId,listItems.url?.toUri()?.lastPathSegment)){
                    Toast.makeText(requireActivity(), "Already Downloaded", Toast.LENGTH_SHORT).show()
                    (requireActivity() as MainActivity).switchToDownloads()
                }else{
                    download(videoId.text.toString())
                }
            }else{
                download(videoId.text.toString())
            }

        }

    }


    @UnstableApi
    private fun download(videoId: String){
        educryptMedia.MediaDownloadBuilder()
            .setVideoId(videoId)
            .setAccessKey(Const.ACCESS_KEY)
            .setSecretKey(Const.SECRET_KEY)
            .onDownload { downloads ->
                val listItems: MutableList<ListItem> = mutableListOf()
                CoroutineScope(Dispatchers.Main).launch {
                    downloads.data?.download_url?.let { downloads ->
                        downloads.forEach {
                            listItems.add(ListItem(vdcId = binding.videoId.text.toString(), text = it.title, url = it.url, size = it.size))
                        }
                    }
                    if (listItems.isEmpty()) {
                        Toast.makeText(requireActivity(), "No Download Link Found", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        val bottomSheet = ListBottomSheetDialogFragment(
                            requireActivity(),
                            listItems,
                            (requireActivity() as MainActivity)
                        )
                        bottomSheet.show(requireActivity().supportFragmentManager, bottomSheet.tag)
                    }
                }
            }
            .execute()

    }

}