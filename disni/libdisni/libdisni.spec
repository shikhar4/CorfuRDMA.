%{!?JDK_HOME: %global JDK_HOME %(readlink -f /usr/bin/java | sed -e "s@/jre/bin/java@@")}
%{!?configure_options: %global configure_options "--with-jdk=%{JDK_HOME}"}

Name: libdisni
Version: 2.1
Release: 1%{?dist}
Summary: Direct Storage and Networking Interface Library

Group: System Environment/Libraries
License: GPLv2 or BSD
Url: http://www.github.com/zrlio/disni.git
Source: http://www.github.org/zrlio/%{name}/releases/download/v{%version}/%{name}-%{version}.tar.gz
BuildRoot: %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)

%description
libdisni provides a user-space API to direct storage and networking access from
userpace. It provides an RDMA interface to access remote memory.

%package devel
Summary: Development files for the libdisni library
Group: System Environment/Libraries
Requires: libdisni = %{version}

%description devel
Development files for the libdisni library.

%prep
%setup -q -n %{name}-%{version}

%build
if ! (echo %{configure_options} | grep -qw with-jdk); then
	configure_options="%{configure_options} --with-jdk=%{JDK_HOME}"
fi

%configure %{configure_options}
make %{?_smp_mflags}

%install
rm -rf %{buildroot}
%makeinstall
# remove unpackaged files from the buildroot
rm -f %{buildroot}%{_libdir}/*a
rm -rf %{buildroot}%{_includedir}/*

%clean
rm -rf %{buildroot}

%post -p /sbin/ldconfig
%postun -p /sbin/ldconfig

%files
%defattr(-,root,root,-)
%{_libdir}/lib*.so.*
%doc AUTHORS COPYING README

%files devel
%defattr(-,root,root)
%{_libdir}/libdisni*.so

%changelog
* Thu Apr 10 2018 Update to 1.5 Jonas Pfefferle <pepperjo@japf.ch> 1.5
- Release 1.5

* Thu Jan 18 2018 Update to 1.4 Jonas Pfefferle <pepperjo@japf.ch> 1.4
- Release 1.4

* Thu Sep 14 2017 Update to 1.3 Yuval Degani <yuvaldeg@mellanox.com> 1.3
- Release 1.3
