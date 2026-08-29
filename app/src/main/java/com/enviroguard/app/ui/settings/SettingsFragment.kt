package com.enviroguard.app.ui.settings
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.enviroguard.app.EnviroGuardApp
import com.enviroguard.app.R
import com.enviroguard.app.databinding.FragmentSettingsBinding

class SettingsFragment: Fragment(){
 private var _binding:FragmentSettingsBinding?=null; private val binding get()=_binding!!; private lateinit var vm:SettingsViewModel
 override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{_binding=FragmentSettingsBinding.inflate(i,c,false);return binding.root}
 override fun onViewCreated(v:View,s:Bundle?){val app=requireActivity().application as EnviroGuardApp;vm=ViewModelProvider(this,SettingsViewModelFactory(requireContext(),app.repository))[SettingsViewModel::class.java]
  vm.notificationsEnabled.observe(viewLifecycleOwner){binding.switchNotifications.isChecked=it};vm.demoMode.observe(viewLifecycleOwner){binding.switchDemoMode.isChecked=it}
  binding.switchNotifications.setOnCheckedChangeListener{_,x->vm.setNotifications(x)};binding.switchDemoMode.setOnCheckedChangeListener{_,x->vm.setDemoMode(x)}
  binding.btnCelsius.setOnClickListener{vm.setUseCelsius(true)};binding.btnFahrenheit.setOnClickListener{vm.setUseCelsius(false)}
 }
 override fun onDestroyView(){super.onDestroyView();_binding=null}
}
